package com.myhealth.data.fit

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.SportGroup
import com.myhealth.domain.model.SportType
import kotlinx.datetime.TimeZone
import org.junit.Test
import java.time.LocalDate

/**
 * The named cases of PLAN P7.2 over the JSON fixtures in
 * `app/src/test/resources/fixtures/fit/` — one per sport plus one with gaps in the record stream.
 */
class FitToDomainMapperTest {

    private val zone = TimeZone.of("Europe/Berlin")
    private val mapper = FitToDomainMapper()
    private val now = 1_800_000_000_000L

    private fun map(fixture: String) = mapper.toIngestItems(FitFixtures.load(fixture), zone, now)

    @Test
    fun fit01_running_session_maps_to_run_outdoor() {
        val item = map("run_outdoor").single()
        val session = item.session

        assertThat(session.sportType).isEqualTo(SportType.RUN_OUTDOOR)
        assertThat(session.sportGroup).isEqualTo(SportGroup.RUN)
        assertThat(session.title).isEqualTo("Morning Run")
        assertThat(session.startAtMillis).isEqualTo(RUN_START)
        assertThat(session.endAtMillis).isEqualTo(RUN_START + 1_500_000L)
        assertThat(session.day).isEqualTo(LocalDate.of(2026, 5, 10).toEpochDay())
        assertThat(session.durationSec).isEqualTo(1490)
        assertThat(session.elapsedSec).isEqualTo(1500)
        assertThat(session.distanceMeters).isEqualTo(5000.0)
        assertThat(session.activeEnergyKcal).isEqualTo(355.0)
        assertThat(session.avgHr).isEqualTo(152)
        assertThat(session.maxHr).isEqualTo(178)
        assertThat(session.elevationGainM).isEqualTo(42.0)
        // FIT records running cadence per leg; the domain stores steps per minute.
        assertThat(session.avgCadenceSpm).isEqualTo(170.0)
        assertThat(session.primarySource).isEqualTo(ActivitySource.FIT_IMPORT)
        assertThat(session.dedupeBucket).isEqualTo("RUN|${RUN_START / 300_000L}")
        assertThat(item.record.source).isEqualTo(ActivitySource.FIT_IMPORT)
        assertThat(item.record.receivedAtMillis).isEqualTo(now)
        assertThat(item.record.payloadJson).contains(item.record.externalId)

        assertThat(session.laps.map { it.lapIndex }).containsExactly(0, 1).inOrder()
        assertThat(session.laps[0].durationSec).isEqualTo(748)
        assertThat(session.laps[1].distanceMeters).isEqualTo(2500.0)

        val streams = checkNotNull(session.streams)
        assertThat(session.hasStreams).isTrue()
        assertThat(streams.sampleCount).isEqualTo(6)
        assertThat(streams.sampleOffsetsSec.toList()).containsExactly(0, 1, 2, 3, 4, 5).inOrder()
        assertThat(streams.hr).containsExactly(140, 144, 148, 152, 156, 160).inOrder()
        assertThat(streams.medianIntervalSec).isEqualTo(1.0)
        assertThat(checkNotNull(streams.distanceMeters).last()).isWithin(1e-9).of(17.5)
    }

    @Test
    fun fit02_treadmill_subsport() {
        val session = map("run_treadmill").single().session

        assertThat(session.sportType).isEqualTo(SportType.RUN_TREADMILL)
        assertThat(session.sportGroup).isEqualTo(SportGroup.RUN)
        // A treadmill file carries no GPS, so the position channel stays absent.
        assertThat(checkNotNull(session.streams).latLngE7).isNull()
    }

    @Test
    fun fit03_soccer_maps_to_soccer_training() {
        val session = map("soccer").single().session

        assertThat(session.sportType).isEqualTo(SportType.SOCCER_TRAINING)
        assertThat(session.sportGroup).isEqualTo(SportGroup.SOCCER)
        assertThat(session.durationSec).isEqualTo(5400)
        assertThat(session.distanceMeters).isEqualTo(8200.0)
    }

    @Test
    fun fit04_external_id_is_stable() {
        val first = map("run_outdoor").single().record.externalId
        val second = map("run_outdoor").single().record.externalId

        assertThat(first).isEqualTo(second)
        assertThat(first).hasLength(32)
        assertThat(first).isEqualTo(EXPECTED_EXTERNAL_ID)

        // A different start time under the same file id is a different activity.
        val data = FitFixtures.load("run_outdoor")
        val shifted = mapper.externalId(data.fileId, RUN_START + 1_000L)
        assertThat(shifted).isNotEqualTo(first)

        // …and so is the same start time from a different device.
        val otherDevice = mapper.externalId(
            checkNotNull(data.fileId).copy(serialNumber = 111L),
            RUN_START,
        )
        assertThat(otherDevice).isNotEqualTo(first)
    }

    @Test
    fun fit05_record_stream_gaps_preserved_as_nulls() {
        val streams = checkNotNull(map("gaps").single().session.streams)

        // Six records, one of them six seconds after the previous one.
        assertThat(streams.sampleOffsetsSec.toList()).containsExactly(0, 1, 2, 3, 9, 10).inOrder()
        assertThat(streams.hr).containsExactly(null, 141, null, 143, null, 147).inOrder()
        assertThat(streams.sampleCount).isEqualTo(6)
        assertThat(streams.medianIntervalSec).isEqualTo(1.0)

        // Numeric channels cannot hold a null, so a gap carries the previous value forward.
        assertThat(checkNotNull(streams.speedMps).toList())
            .containsExactly(3.2, 3.2, 3.2, 3.2, 3.3, 3.3).inOrder()
        assertThat(checkNotNull(streams.altitudeM)[3]).isEqualTo(30.4)

        // The first record has no fix; the leading gap takes the first known position.
        val positions = checkNotNull(streams.latLngE7)
        assertThat(positions).hasSize(6)
        assertThat(positions[0]).isEqualTo(positions[1])
    }

    @Test
    fun fit06_semicircle_conversion() {
        val positions = checkNotNull(checkNotNull(map("run_outdoor").single().session.streams).latLngE7)

        // 52.5200 N / 13.4050 E, round-tripped through FIT semicircles.
        assertThat(positions.first().latE7).isEqualTo(525_200_000)
        assertThat(positions.first().lngE7).isEqualTo(134_050_000)

        assertThat(Semicircles.toDegrees(626_588_007)).isWithin(1e-6).of(52.52)
        assertThat(Semicircles.toDegrees(0)).isEqualTo(0.0)
        assertThat(Semicircles.toDegrees(-626_588_007)).isWithin(1e-6).of(-52.52)
        assertThat(Semicircles.toE7(-626_588_007)).isEqualTo(-525_200_000)
    }

    @Test
    fun fit07_fit_epoch_conversion() {
        // The FIT epoch is 1989-12-31T00:00:00Z.
        assertThat(FitEpoch.OFFSET_SECONDS).isEqualTo(631_065_600L)
        assertThat(FitEpoch.toUnixMillis(0L)).isEqualTo(631_065_600_000L)

        val fitSeconds = RUN_START / 1000L - 631_065_600L
        assertThat(FitEpoch.toUnixMillis(fitSeconds)).isEqualTo(RUN_START)
        assertThat(FitEpoch.toFitSeconds(RUN_START)).isEqualTo(fitSeconds)
    }

    @Test
    fun fit08_unknown_sport_falls_back_to_other() {
        val session = map("unknown_sport").single().session

        assertThat(session.sportType).isEqualTo(SportType.OTHER)
        assertThat(session.sportGroup).isEqualTo(SportGroup.OTHER)
        assertThat(FitSportMap.toSportType(null, null)).isEqualTo(SportType.OTHER)
        assertThat(FitSportMap.toSportType("SOMETHING_NEW", "ALSO_NEW")).isEqualTo(SportType.OTHER)
    }

    @Test
    fun cycling_walking_swimming_and_strength_fixtures_map_to_their_sports() {
        assertThat(map("cycling").single().session.sportType).isEqualTo(SportType.CYCLING)
        assertThat(map("walking").single().session.sportType).isEqualTo(SportType.WALK)
        assertThat(map("swimming").single().session.sportType).isEqualTo(SportType.SWIM)
        assertThat(map("strength").single().session.sportType).isEqualTo(SportType.STRENGTH)
        // Cycling cadence is already per-minute revolutions and must not be doubled.
        assertThat(map("cycling").single().session.avgCadenceSpm).isEqualTo(82.0)
    }

    @Test
    fun sub_sports_refine_the_running_and_gym_families() {
        assertThat(FitSportMap.toSportType("RUNNING", "TRAIL")).isEqualTo(SportType.RUN_TRAIL)
        assertThat(FitSportMap.toSportType("RUNNING", "TRACK")).isEqualTo(SportType.RUN_TRACK)
        assertThat(FitSportMap.toSportType("RUNNING", "INDOOR_RUNNING")).isEqualTo(SportType.RUN_TREADMILL)
        assertThat(FitSportMap.toSportType("CYCLING", "INDOOR_CYCLING")).isEqualTo(SportType.CYCLING_INDOOR)
        assertThat(FitSportMap.toSportType("TRAINING", "CARDIO_TRAINING")).isEqualTo(SportType.HIIT)
        assertThat(FitSportMap.toSportType("TRAINING", "YOGA")).isEqualTo(SportType.MOBILITY)
        assertThat(FitSportMap.toSportType("FITNESS_EQUIPMENT", "INDOOR_ROWING")).isEqualTo(SportType.ROWING)
        assertThat(FitSportMap.toSportType("HIKING", null)).isEqualTo(SportType.HIKE)
    }

    @Test
    fun a_file_without_sessions_yields_no_ingest_items() {
        val empty = FitFileData(fileId = FitFileId(serialNumber = 1L, timeCreatedMillis = 2L))

        assertThat(mapper.toIngestItems(empty, zone, now)).isEmpty()
    }

    private companion object {
        /** 2026-05-10T07:00:00Z — the start instant every fixture shares. */
        const val RUN_START = 1_778_396_400_000L
        const val EXPECTED_EXTERNAL_ID = "88e435158e47315a6a7b08181b81cb95"
    }
}
