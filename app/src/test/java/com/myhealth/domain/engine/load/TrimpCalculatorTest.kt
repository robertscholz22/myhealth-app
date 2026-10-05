package com.myhealth.domain.engine.load

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.engine.bike.FtpEstimate
import com.myhealth.domain.engine.bike.FtpSource
import com.myhealth.domain.model.ActivityStreams
import com.myhealth.domain.model.EngineWarningCode
import com.myhealth.domain.model.LoadMethod
import com.myhealth.domain.model.Sex
import com.myhealth.domain.model.SportType
import org.junit.Test

/**
 * The TRIMP cases of PLAN §3.2.4 (`load01`…`load08`). Reference athlete throughout: hrMax = 190,
 * hrRest = 50 (reserve 140), so 150 bpm is `hrr = 100/140 = 0.714286`.
 */
class TrimpCalculatorTest {

    private val bounds = HrBounds(hrMax = 190, hrRest = 50)

    private fun input(
        sportType: SportType = SportType.RUN_OUTDOOR,
        durationSec: Int = 3600,
        sex: Sex = Sex.MALE,
        avgHr: Int? = null,
        rpe: Int? = null,
        streams: ActivityStreams? = null,
        avgPowerW: Int? = null,
        normalizedPowerW: Int? = null,
        ftp: FtpEstimate? = null,
    ) = TrimpInput(
        sportType = sportType,
        durationSec = durationSec,
        sex = sex,
        bounds = bounds,
        avgHr = avgHr,
        rpe = rpe,
        streams = streams,
        avgPowerW = avgPowerW,
        normalizedPowerW = normalizedPowerW,
        ftp = ftp,
    )

    private fun ftp(watts: Int = 300, source: FtpSource = FtpSource.STREAM_20MIN) =
        FtpEstimate(watts = watts, source = source)

    private fun ride(
        durationSec: Int = 3600,
        normalizedPowerW: Int? = 255,
        avgPowerW: Int? = null,
        avgHr: Int? = null,
        ftp: FtpEstimate? = ftp(),
        streams: ActivityStreams? = null,
    ) = input(
        sportType = SportType.CYCLING_INDOOR,
        durationSec = durationSec,
        avgHr = avgHr,
        streams = streams,
        avgPowerW = avgPowerW,
        normalizedPowerW = normalizedPowerW,
        ftp = ftp,
    )

    private fun streams(offsets: IntArray, hr: List<Int?>) = ActivityStreams(
        sampleOffsetsSec = offsets,
        hr = hr,
        sampleCount = offsets.size,
        medianIntervalSec = 60.0,
    )

    @Test
    fun load01_banister_male_60min_at_150bpm() {
        val result = TrimpCalculator.compute(input(avgHr = 150))

        // hrr = 0.714286; rate = 0.714286 * 0.64 * e^(1.92*0.714286) = 1.8016 AU/min.
        assertThat(TrimpCalculator.trimpRate(bounds.hrr(150), 1.92)).isWithin(0.001).of(1.8016)
        assertThat(result.trimp).isWithin(0.5).of(108.1)
        assertThat(result.method).isEqualTo(LoadMethod.HR_AVERAGE)
    }

    @Test
    fun load02_banister_female_uses_y_1_67() {
        val result = TrimpCalculator.compute(input(avgHr = 150, sex = Sex.FEMALE))

        assertThat(TrimpCalculator.trimpRate(bounds.hrr(150), 1.67)).isWithin(0.001).of(1.5070)
        assertThat(result.trimp).isWithin(0.5).of(90.4)
    }

    @Test
    fun load03_hr_samples_beat_average_when_available() {
        // 10 min at 120 bpm, one transition minute, 9 min at 180 bpm: average 150 bpm over 20 min.
        val offsets = IntArray(21) { it * 60 }
        val hr = List(21) { if (it <= 10) 120 else 180 }
        val stepped = TrimpCalculator.compute(input(durationSec = 1200, avgHr = 150, streams = streams(offsets, hr)))
        val averaged = TrimpCalculator.compute(input(durationSec = 1200, avgHr = 150))

        assertThat(stepped.method).isEqualTo(LoadMethod.HR_SAMPLES)
        assertThat(averaged.method).isEqualTo(LoadMethod.HR_AVERAGE)
        // 10*0.83574 + 1*1.8016 + 9*3.53402 = 41.96 AU, well above the linearised 36.03 AU.
        assertThat(stepped.trimp).isWithin(0.05).of(41.97)
        assertThat(averaged.trimp).isWithin(0.05).of(36.03)
        assertThat(stepped.trimp).isNotEqualTo(averaged.trimp)
    }

    @Test
    fun load04_sample_gap_capped_at_60s() {
        // 10 real minutes, then one sample 600 s later: the gap may contribute at most 1 minute.
        val offsets = IntArray(12) { if (it <= 10) it * 60 else 1200 }
        val hr = List(12) { 150 }
        val result = TrimpCalculator.compute(input(durationSec = 1200, streams = streams(offsets, hr)))

        assertThat(result.method).isEqualTo(LoadMethod.HR_SAMPLES)
        assertThat(result.trimp).isWithin(0.05).of(11 * 1.8016)
        // Without the cap the same stream would integrate 20 minutes.
        assertThat(result.trimp).isLessThan(20 * 1.8016)
    }

    @Test
    fun load05_null_hr_samples_are_skipped() {
        // Two dropouts remove the three intervals that touch them: 8 of 11 minutes remain.
        val offsets = IntArray(12) { it * 60 }
        val hr: List<Int?> = List(12) { if (it == 5 || it == 6) null else 150 }
        val result = TrimpCalculator.compute(input(durationSec = 660, streams = streams(offsets, hr)))

        assertThat(result.trimp.isNaN()).isFalse()
        assertThat(result.method).isEqualTo(LoadMethod.HR_SAMPLES)
        assertThat(result.trimp).isWithin(0.05).of(8 * 1.8016)
    }

    @Test
    fun load06_rpe_fallback_uses_0_30_factor() {
        val result = TrimpCalculator.compute(input(rpe = 7))

        assertThat(result.method).isEqualTo(LoadMethod.RPE_ESTIMATE)
        assertThat(result.trimp).isWithin(0.001).of(126.0)
        assertThat(result.warnings.map { it.code }).contains(EngineWarningCode.MISSING_HR)
    }

    @Test
    fun load07_sport_default_rpe_used_when_none_given() {
        val result = TrimpCalculator.compute(
            input(sportType = SportType.SOCCER_MATCH, durationSec = 90 * 60),
        )

        assertThat(result.method).isEqualTo(LoadMethod.RPE_ESTIMATE)
        assertThat(result.trimp).isWithin(0.001).of(229.5)
        assertThat(result.warnings.map { it.code }).contains(EngineWarningCode.ESTIMATED_LOAD)
    }

    @Test
    fun load08_hrr_clamped_at_zero_and_one() {
        val belowRest = TrimpCalculator.compute(input(avgHr = 40))
        val aboveMax = TrimpCalculator.compute(input(avgHr = 220))

        assertThat(bounds.hrr(40)).isEqualTo(0.0)
        assertThat(bounds.hrr(220)).isEqualTo(1.0)
        assertThat(belowRest.trimp).isEqualTo(0.0)
        // hrr = 1 -> rate = 0.64 * e^1.92 = 4.3654 AU/min over 60 min.
        assertThat(aboveMax.trimp).isWithin(0.1).of(60 * 4.3654)
        assertThat(aboveMax.trimp).isAtMost(600.0)
    }

    @Test
    fun duration_only_for_a_sport_without_a_default_rpe() {
        val result = TrimpCalculator.compute(input(sportType = SportType.OTHER, durationSec = 3600))

        assertThat(result.method).isEqualTo(LoadMethod.DURATION_ONLY)
        assertThat(result.trimp).isWithin(0.001).of(90.0)
        assertThat(result.warnings.map { it.code }).contains(EngineWarningCode.ESTIMATED_LOAD)
    }

    @Test
    fun trimp_is_clamped_at_600_with_a_warning() {
        val result = TrimpCalculator.compute(input(durationSec = 8 * 3600, avgHr = 185))

        assertThat(result.trimp).isEqualTo(600.0)
        assertThat(result.warnings.map { it.code }).contains(EngineWarningCode.IMPLAUSIBLE_VALUE)
    }

    @Test
    fun rpe_outside_one_to_ten_is_clamped() {
        val result = TrimpCalculator.compute(input(rpe = 14))

        assertThat(result.trimp).isWithin(0.001).of(0.30 * 10 * 60)
        assertThat(result.warnings.map { it.code }).contains(EngineWarningCode.IMPLAUSIBLE_VALUE)
    }

    @Test
    fun nine_hr_samples_fall_back_to_the_average() {
        val offsets = IntArray(9) { it * 60 }
        val result = TrimpCalculator.compute(
            input(durationSec = 480, avgHr = 150, streams = streams(offsets, List(9) { 150 })),
        )

        assertThat(result.method).isEqualTo(LoadMethod.HR_AVERAGE)
    }

    // ---- POWER_TSS rung (P12.2, §3.2.2) --------------------------------------------------------

    @Test
    fun pw01_tss_60min_if_0_85_gives_108_4_au() {
        val result = TrimpCalculator.compute(ride())

        // IF = 255/300 = 0.85; TSS = 3600*255*0.85/(300*3600)*100 = 72.25; AU = 72.25 * 1.5.
        assertThat(TrimpCalculator.powerTss(ride())!!).isWithin(1e-6).of(72.25)
        assertThat(result.method).isEqualTo(LoadMethod.POWER_TSS)
        assertThat(result.trimp).isWithin(0.05).of(108.375)
        // The same threshold hour by heart rate (load01) costs 108.1 AU.
        assertThat(result.trimp).isWithin(0.5).of(108.1)
    }

    @Test
    fun pw02_no_ftp_falls_to_rpe() {
        val result = TrimpCalculator.compute(ride(ftp = null))

        // CYCLING_INDOOR's default RPE is 5.0: 0.30 * 5 * 60 = 90.0.
        assertThat(result.method).isEqualTo(LoadMethod.RPE_ESTIMATE)
        assertThat(result.trimp).isWithin(0.001).of(90.0)
        assertThat(result.warnings.map { it.code }).contains(EngineWarningCode.MISSING_HR)
    }

    @Test
    fun pw03_avg_hr_beats_power() {
        val withHr = TrimpCalculator.compute(ride(avgHr = 150))

        assertThat(withHr.method).isEqualTo(LoadMethod.HR_AVERAGE)
        assertThat(withHr.trimp).isWithin(0.5).of(108.1)

        // A stream of HR samples outranks power as well.
        val offsets = IntArray(21) { it * 60 }
        val streamed = TrimpCalculator.compute(
            ride(durationSec = 1200, avgHr = 150, streams = streams(offsets, List(21) { 150 })),
        )
        assertThat(streamed.method).isEqualTo(LoadMethod.HR_SAMPLES)
    }

    @Test
    fun pw04_np_null_uses_avg_power() {
        val result = TrimpCalculator.compute(ride(normalizedPowerW = null, avgPowerW = 255))

        assertThat(result.method).isEqualTo(LoadMethod.POWER_TSS)
        assertThat(result.trimp).isWithin(0.05).of(108.375)

        // With both present, normalized power wins.
        val both = TrimpCalculator.compute(ride(normalizedPowerW = 255, avgPowerW = 150))
        assertThat(both.trimp).isWithin(0.05).of(108.375)
    }

    @Test
    fun pw05_4_5h_at_if_1_clamped_600_implausible() {
        // 4.5 h at FTP is 450 TSS = 675 AU, above the 600 AU ceiling.
        val result = TrimpCalculator.compute(
            ride(durationSec = (4.5 * 3600).toInt(), normalizedPowerW = 300),
        )

        assertThat(result.method).isEqualTo(LoadMethod.POWER_TSS)
        assertThat(result.trimp).isEqualTo(600.0)
        assertThat(result.warnings.map { it.code }).contains(EngineWarningCode.IMPLAUSIBLE_VALUE)
    }

    @Test
    fun pw06_session_np_ftp_warns_estimated_stream_ftp_does_not() {
        val fromSession = TrimpCalculator.compute(ride(ftp = ftp(source = FtpSource.SESSION_NP)))
        val fromStream = TrimpCalculator.compute(ride(ftp = ftp(source = FtpSource.STREAM_20MIN)))
        val fromManual = TrimpCalculator.compute(ride(ftp = ftp(source = FtpSource.MANUAL)))

        assertThat(fromSession.warnings.map { it.code }).contains(EngineWarningCode.ESTIMATED_LOAD)
        assertThat(fromStream.warnings.map { it.code })
            .doesNotContain(EngineWarningCode.ESTIMATED_LOAD)
        assertThat(fromManual.warnings.map { it.code })
            .doesNotContain(EngineWarningCode.ESTIMATED_LOAD)
        // The power rung is a measurement, not a stand-in for a missing HR reading.
        assertThat(fromStream.warnings.map { it.code }).doesNotContain(EngineWarningCode.MISSING_HR)
        // All three produce the same load; only the confidence differs.
        assertThat(fromSession.trimp).isEqualTo(fromStream.trimp)
    }

    @Test
    fun zero_duration_session_has_zero_load() {
        val result = TrimpCalculator.compute(input(durationSec = 0, avgHr = 150))

        assertThat(result.trimp).isEqualTo(0.0)
    }
}
