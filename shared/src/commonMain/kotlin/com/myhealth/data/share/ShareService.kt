package com.myhealth.data.share

import com.myhealth.data.db.dao.ShareDao
import com.myhealth.data.db.entity.ShareConfigEntity
import com.myhealth.data.time.PlatformClock
import com.myhealth.domain.engine.strength.UserExerciseIds
import com.myhealth.domain.model.Exercise
import com.myhealth.domain.repository.ImportResult
import com.myhealth.domain.repository.IngredientRepository
import com.myhealth.domain.repository.LibrarySnapshot
import com.myhealth.domain.repository.MealRepository
import com.myhealth.domain.repository.ShareRepository
import com.myhealth.domain.repository.ShareSetup
import com.myhealth.domain.repository.StrengthRepository
import com.myhealth.domain.repository.UserExerciseRepository
import com.myhealth.domain.share.LocalShareState
import com.myhealth.domain.share.ShareConnection
import com.myhealth.domain.share.ShareKind
import com.myhealth.domain.share.ShareLink
import com.myhealth.domain.share.SharePayloads
import com.myhealth.domain.share.SharePayloads.toIngredient
import com.myhealth.domain.share.SharePayloads.toTemplate
import com.myhealth.domain.share.SharePayloads.toUserExercise
import com.myhealth.domain.share.SharePayloads.toWorkout
import com.myhealth.domain.share.SharePlanner
import com.myhealth.domain.share.SharePlanner.ImportStep
import com.myhealth.domain.share.ShareRef
import com.myhealth.domain.share.SharedItem
import com.myhealth.domain.util.AppError
import com.myhealth.domain.util.Outcome
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * P27: [ShareRepository] over Firestore ([FirestoreClient]) and the existing content repositories.
 *
 * Every rule about who may change what lives in [SharePlanner]; this class only fetches, encodes,
 * writes and records links. One [Mutex] serialises all library operations, so a double tap can
 * never upload or import the same item twice.
 */
@OptIn(ExperimentalUuidApi::class)
class ShareService(
    private val dao: ShareDao,
    private val exercises: UserExerciseRepository,
    private val ingredients: IngredientRepository,
    private val meals: MealRepository,
    private val workouts: StrengthRepository,
    private val clock: PlatformClock,
    private val clientFor: (projectId: String, apiKey: String) -> FirestoreClient = { p, k ->
        FirestoreClient(p, k, endpointsFor(p), { clock.millis() })
    },
    private val newShareId: () -> String = { Uuid.random().toString() },
) : ShareRepository {

    private val lock = Mutex()
    private var session: FirebaseSession? = null
    private val _library = MutableStateFlow<LibrarySnapshot?>(null)

    override val setup: Flow<ShareSetup?> = dao.observeConfig().map { it?.toSetup() }

    override val links: Flow<List<ShareLink>> = dao.observeLinks().map { rows -> rows.mapNotNull { it.toDomain() } }

    override val library: StateFlow<LibrarySnapshot?> = _library.asStateFlow()

    // --- connection -------------------------------------------------------------------------------

    override suspend fun connect(connection: ShareConnection, displayName: String): Outcome<Unit> = lock.withLock {
        val name = displayName.trim()
        if (name.isEmpty() || name.length > MAX_NAME) {
            return@withLock Outcome.Err(AppError.Validation("name", "Enter a name of up to $MAX_NAME characters."))
        }
        val client = clientFor(connection.projectId, connection.apiKey)
        val existing = dao.getConfig()
        // Same project again (e.g. after a restore): keep this device's author id.
        val signedIn = if (existing != null && existing.projectId == connection.projectId) {
            client.refresh(existing.refreshToken)
        } else {
            client.signUpAnonymous()
        }
        val s = when (signedIn) {
            is Outcome.Err -> return@withLock signedIn
            is Outcome.Ok -> signedIn.value
        }
        when (val joined = client.putMember(s, name, connection.joinCode)) {
            is Outcome.Err -> return@withLock joined
            is Outcome.Ok -> Unit
        }
        if (existing != null && existing.uid != s.uid) dropMyLinks(existing.uid)
        dao.upsertConfig(
            ShareConfigEntity(
                projectId = connection.projectId, apiKey = connection.apiKey, joinCode = connection.joinCode,
                uid = s.uid, refreshToken = s.refreshToken, displayName = name, joinedAtMillis = clock.millis(),
            ),
        )
        session = s
        _library.value = null
        Outcome.Ok(Unit)
    }

    override suspend fun rename(displayName: String): Outcome<Unit> = lock.withLock {
        val config = dao.getConfig() ?: return@withLock notConnected()
        val name = displayName.trim()
        if (name.isEmpty() || name.length > MAX_NAME) {
            return@withLock Outcome.Err(AppError.Validation("name", "Enter a name of up to $MAX_NAME characters."))
        }
        withSession(config) { client, s -> client.putMember(s, name, config.joinCode) }.also {
            if (it is Outcome.Ok) dao.upsertConfig(config.copy(displayName = name))
        }
    }

    override suspend fun leave(): Outcome<Unit> = lock.withLock {
        val config = dao.getConfig() ?: return@withLock Outcome.Ok(Unit)
        dropMyLinks(config.uid)
        dao.deleteConfig()
        session = null
        _library.value = null
        Outcome.Ok(Unit)
    }

    /** My rows lose their library link (a fork keeps its credit); theirs stay as they are. */
    private suspend fun dropMyLinks(uid: String) {
        dao.getLinks().mapNotNull { it.toDomain() }.filter { it.isMineShared(uid) }.forEach { link ->
            if (link.basedOnShareId != null) {
                dao.upsertLink(link.copy(shareId = null, authorId = null, authorName = null, version = null, payloadHash = null).toEntity())
            } else {
                dao.deleteLink(link.kind.name, link.localKey)
            }
        }
    }

    // --- refresh ------------------------------------------------------------------------------------

    override suspend fun refresh(): Outcome<LibrarySnapshot> = lock.withLock { refreshLocked() }

    private suspend fun refreshLocked(): Outcome<LibrarySnapshot> {
        val config = dao.getConfig() ?: return notConnected()
        val items = when (val r = withSession(config) { c, s -> c.listItems(s) }) {
            is Outcome.Err -> return r
            is Outcome.Ok -> r.value
        }
        val members = when (val r = withSession(config) { c, s -> c.listMembers(s) }) {
            is Outcome.Err -> return r
            is Outcome.Ok -> r.value
        }
        pruneLinks()
        val ids = items.mapTo(mutableSetOf()) { it.shareId }
        SharePlanner.gone(currentLinks(), ids, config.uid).forEach { dao.upsertLink(it.copy(remoteGone = true).toEntity()) }
        // A theirs row whose item came back (shared again) is not gone any more.
        currentLinks().filter { it.remoteGone && it.shareId in ids }.forEach { dao.upsertLink(it.copy(remoteGone = false).toEntity()) }
        return Outcome.Ok(LibrarySnapshot(items, members, clock.millis()).also { _library.value = it })
    }

    /** Links whose local row was deleted are dropped. */
    private suspend fun pruneLinks() {
        currentLinks().filter { !exists(it.ref) }.forEach { dao.deleteLink(it.kind.name, it.localKey) }
    }

    // --- push ---------------------------------------------------------------------------------------

    override suspend fun share(ref: ShareRef): Outcome<Int> = lock.withLock { shareLocked(ref) }

    private suspend fun shareLocked(ref: ShareRef): Outcome<Int> {
        val config = dao.getConfig() ?: return notConnected()
        val links = currentLinks().associateBy { it.ref }
        if (links[ref]?.isTheirs(config.uid) == true) {
            return Outcome.Err(AppError.Validation("author", "Only the author can share this item."))
        }
        // Dependencies are one level deep (a meal's ingredients, a workout's own exercises).
        val deps = mapOf(ref to dependenciesOf(ref))
        val closure = SharePlanner.pushClosure(ref, { deps[it].orEmpty() }, links, config.uid)
        var uploaded = 0
        for (r in closure) {
            val link = currentLinks().firstOrNull { it.ref == r }
            val payload = payloadOf(r) ?: return Outcome.Err(AppError.Validation("item", "This item no longer exists."))
            // A dependency already in the library and unchanged needs no upload.
            if (r != ref && link?.isMineShared(config.uid) == true && link.payloadHash == SharePlanner.payloadHash(payload)) continue
            val shareId = link?.shareId?.takeIf { link.isMineShared(config.uid) } ?: newShareId()
            val version = if (link?.isMineShared(config.uid) == true) (link.version ?: 0L) + 1 else 1L
            val item = SharedItem(
                shareId = shareId, kind = r.kind, ownerUid = config.uid, authorName = config.displayName,
                name = nameOf(r).orEmpty(), version = version, updatedAtMillis = clock.millis(),
                schema = SharePayloads.SCHEMA, payload = payload,
            )
            val create = link?.isMineShared(config.uid) != true
            when (val put = withSession(config) { c, s -> c.putItem(s, item, create) }) {
                is Outcome.Err -> return put
                is Outcome.Ok -> Unit
            }
            dao.upsertLink(
                SharePlanner.pushedLink(link, r, shareId, config.uid, config.displayName, version, clock.millis(), payload).toEntity(),
            )
            uploaded++
        }
        return Outcome.Ok(uploaded)
    }

    override suspend fun changedMine(): List<ShareRef> = lock.withLock {
        val config = dao.getConfig() ?: return@withLock emptyList()
        changedMineLocked(config.uid)
    }

    private suspend fun changedMineLocked(me: String): List<ShareRef> {
        pruneLinks()
        val links = currentLinks()
        val hashes = mutableMapOf<ShareRef, String?>()
        links.filter { it.isMineShared(me) }.forEach { hashes[it.ref] = payloadOf(it.ref)?.let(SharePlanner::payloadHash) }
        return SharePlanner.changedMine(links, { hashes[it] }, me)
    }

    override suspend fun pushChanges(): Outcome<Int> = lock.withLock {
        val config = dao.getConfig() ?: return@withLock notConnected()
        var total = 0
        for (ref in changedMineLocked(config.uid)) {
            when (val r = shareLocked(ref)) {
                is Outcome.Err -> return@withLock r
                is Outcome.Ok -> total += r.value
            }
        }
        Outcome.Ok(total)
    }

    override suspend fun stopSharing(ref: ShareRef): Outcome<Unit> = lock.withLock {
        val config = dao.getConfig() ?: return@withLock notConnected()
        val link = currentLinks().firstOrNull { it.ref == ref }
        if (link == null || !link.isMineShared(config.uid)) return@withLock Outcome.Ok(Unit)
        when (val del = withSession(config) { c, s -> c.deleteItem(s, link.shareId!!) }) {
            is Outcome.Err -> if ((del.error as? AppError.Network)?.code != NOT_FOUND) return@withLock del
            is Outcome.Ok -> Unit
        }
        if (link.basedOnShareId != null) {
            dao.upsertLink(link.copy(shareId = null, authorId = null, authorName = null, version = null, payloadHash = null).toEntity())
        } else {
            dao.deleteLink(ref.kind.name, ref.localKey)
        }
        Outcome.Ok(Unit)
    }

    override suspend fun deleteRemote(shareId: String): Outcome<Unit> = lock.withLock {
        val config = dao.getConfig() ?: return@withLock notConnected()
        withSession(config) { c, s -> c.deleteItem(s, shareId) }.also {
            if (it is Outcome.Ok) {
                dao.getLinkByShareId(shareId)?.toDomain()?.takeIf { l -> l.isMineShared(config.uid) }?.let { l ->
                    dao.deleteLink(l.kind.name, l.localKey)
                }
            }
        }
    }

    // --- pull ---------------------------------------------------------------------------------------

    override suspend fun import(shareIds: List<String>): Outcome<ImportResult> = lock.withLock { importLocked(shareIds) }

    override suspend fun updateAll(): Outcome<ImportResult> = lock.withLock {
        val config = dao.getConfig() ?: return@withLock notConnected()
        val snapshot = when (val r = refreshLocked()) {
            is Outcome.Err -> return@withLock r
            is Outcome.Ok -> r.value
        }
        importLocked(SharePlanner.updatable(snapshot.items, linksByShareId(), config.uid), snapshot)
    }

    private suspend fun importLocked(shareIds: List<String>, known: LibrarySnapshot? = null): Outcome<ImportResult> {
        val config = dao.getConfig() ?: return notConnected()
        val snapshot = known ?: _library.value ?: when (val r = refreshLocked()) {
            is Outcome.Err -> return r
            is Outcome.Ok -> r.value
        }
        pruneLinks()
        val barcodes = mutableMapOf<String, String>()
        snapshot.items.filter { it.kind == ShareKind.INGREDIENT }
            .mapNotNull { SharePayloads.decodeIngredient(it.payload)?.barcode }
            .distinct()
            .forEach { code -> ingredients.getByBarcode(code)?.let { barcodes[code] = it.id.toString() } }
        val plan = SharePlanner.planImport(
            shareIds, snapshot.items.associateBy { it.shareId }, linksByShareId(), config.uid,
        ) { barcode -> barcodes[barcode] }
        val reused = mutableMapOf<String, Long>()
        var imported = 0
        var updated = 0
        for (step in plan.steps) {
            val result = when (step) {
                is ImportStep.Insert -> insert(step, reused)
                is ImportStep.Update -> update(step, reused)
            }
            when (result) {
                is Outcome.Err -> return result
                is Outcome.Ok -> if (step is ImportStep.Insert) imported++ else updated++
            }
        }
        return Outcome.Ok(ImportResult(imported, updated, plan.problems))
    }

    private suspend fun insert(step: ImportStep.Insert, reused: MutableMap<String, Long>): Outcome<Unit> {
        val item = step.item
        step.reuseLocalKey?.toLongOrNull()?.let {
            // My own ingredient with that barcode stays mine; recipes simply point at it.
            reused[item.shareId] = it
            return Outcome.Ok(Unit)
        }
        val now = clock.millis()
        val localKey: String = when (item.kind) {
            ShareKind.EXERCISE -> {
                val p = SharePayloads.decodeExercise(item.payload) ?: return unreadable(item)
                // Keep the author's random id unless (very unlikely) it is taken on this device.
                val id = if (exercises.getLibrary().userExercise(p.id) == null) p.id else UserExerciseIds.next()
                exercises.upsert(p.toUserExercise(now, id)).orReturn { return it }
                id
            }
            ShareKind.INGREDIENT -> {
                val p = SharePayloads.decodeIngredient(item.payload) ?: return unreadable(item)
                ingredients.upsert(p.toIngredient(now)).orReturn { return it }.toString()
            }
            ShareKind.MEAL -> {
                val p = SharePayloads.decodeMeal(item.payload) ?: return unreadable(item)
                meals.upsertTemplate(p.toTemplate(now, ingredientResolver(reused))).orReturn { return it }.toString()
            }
            ShareKind.WORKOUT -> {
                val p = SharePayloads.decodeWorkout(item.payload) ?: return unreadable(item)
                workouts.upsertWorkout(p.toWorkout(now).withExerciseIds(p)).orReturn { return it }.toString()
            }
        }
        dao.upsertLink(SharePlanner.importedLink(item, localKey, now).toEntity())
        return Outcome.Ok(Unit)
    }

    /** Replaces the content of a theirs row; my personal state on it (favourite, use counts) stays. */
    private suspend fun update(step: ImportStep.Update, reused: MutableMap<String, Long>): Outcome<Unit> {
        val item = step.item
        val now = clock.millis()
        val key = step.localKey
        when (item.kind) {
            ShareKind.EXERCISE -> {
                val p = SharePayloads.decodeExercise(item.payload) ?: return unreadable(item)
                val old = exercises.getLibrary().userExercise(key)
                exercises.upsert(p.toUserExercise(now, key).copy(archived = old?.archived ?: false)).orReturn { return it }
            }
            ShareKind.INGREDIENT -> {
                val p = SharePayloads.decodeIngredient(item.payload) ?: return unreadable(item)
                val old = ingredients.getById(key.toLong()) ?: return unreadable(item)
                ingredients.upsert(
                    p.toIngredient(now, old.id).copy(
                        isFavorite = old.isFavorite, useCount = old.useCount, lastUsedAtMillis = old.lastUsedAtMillis,
                        archived = old.archived, createdAtMillis = old.createdAtMillis,
                    ),
                ).orReturn { return it }
            }
            ShareKind.MEAL -> {
                val p = SharePayloads.decodeMeal(item.payload) ?: return unreadable(item)
                val old = meals.getTemplate(key.toLong()) ?: return unreadable(item)
                meals.upsertTemplate(
                    p.toTemplate(now, ingredientResolver(reused), old.id).copy(
                        isFavorite = old.isFavorite, useCount = old.useCount, lastUsedAtMillis = old.lastUsedAtMillis,
                        archived = old.archived, createdAtMillis = old.createdAtMillis,
                    ),
                ).orReturn { return it }
            }
            ShareKind.WORKOUT -> {
                val p = SharePayloads.decodeWorkout(item.payload) ?: return unreadable(item)
                val old = workouts.getById(key.toLong()) ?: return unreadable(item)
                workouts.upsertWorkout(
                    p.toWorkout(now, old.id).withExerciseIds(p).copy(
                        useInSuggestions = old.useInSuggestions, createdAtMillis = old.createdAtMillis,
                    ),
                ).orReturn { return it }
            }
        }
        dao.upsertLink(SharePlanner.importedLink(item, key, now).toEntity())
        return Outcome.Ok(Unit)
    }

    /** Own-exercise rows point at the local id their share id was imported under (normally the same). */
    private suspend fun com.myhealth.domain.model.StrengthWorkout.withExerciseIds(
        payload: SharePayloads.WorkoutPayload,
    ): com.myhealth.domain.model.StrengthWorkout {
        val byShare = linksByShareId()
        return copy(
            exercises = exercises.mapIndexed { i, row ->
                val local = payload.rows.getOrNull(i)?.exerciseShareId
                    ?.let { byShare[it] }?.takeIf { it.kind == ShareKind.EXERCISE }?.localKey
                if (local != null) row.copy(exerciseId = local) else row
            },
        )
    }

    private suspend fun ingredientResolver(reused: Map<String, Long>): (String) -> Long? {
        val byShare = linksByShareId()
        return { id -> reused[id] ?: byShare[id]?.takeIf { it.kind == ShareKind.INGREDIENT }?.localKey?.toLongOrNull() }
    }

    // --- fork ---------------------------------------------------------------------------------------

    override suspend fun fork(ref: ShareRef): Outcome<ShareRef> = lock.withLock {
        val link = currentLinks().firstOrNull { it.ref == ref }
            ?: return@withLock Outcome.Err(AppError.Validation("item", "Not a shared item."))
        val name = nameOf(ref) ?: return@withLock Outcome.Err(AppError.Validation("item", "This item no longer exists."))
        val newKey: String = when (ref.kind) {
            ShareKind.EXERCISE -> {
                val old = exercises.getLibrary().userExercise(ref.localKey) ?: return@withLock missing()
                val id = UserExerciseIds.next()
                exercises.upsert(old.copy(exercise = old.exercise.copy(id = id), archived = false)).orReturn { return@withLock it }
                id
            }
            ShareKind.INGREDIENT -> {
                val old = ingredients.getById(ref.localKey.toLong()) ?: return@withLock missing()
                // The barcode stays with the original: it is unique on the device.
                ingredients.upsert(old.copy(id = 0L, barcode = null, useCount = 0, lastUsedAtMillis = null, isFavorite = false))
                    .orReturn { return@withLock it }.toString()
            }
            ShareKind.MEAL -> {
                val old = meals.getTemplate(ref.localKey.toLong()) ?: return@withLock missing()
                meals.upsertTemplate(
                    old.copy(id = 0L, items = old.items.map { it.copy(id = 0L, templateId = 0L) }, useCount = 0, lastUsedAtMillis = null),
                ).orReturn { return@withLock it }.toString()
            }
            ShareKind.WORKOUT -> {
                val old = workouts.getById(ref.localKey.toLong()) ?: return@withLock missing()
                workouts.upsertWorkout(old.copy(id = 0L, exercises = old.exercises.map { it.copy(id = 0L, workoutId = 0L) }))
                    .orReturn { return@withLock it }.toString()
            }
        }
        dao.upsertLink(SharePlanner.forkLink(link, name, newKey).toEntity())
        Outcome.Ok(ShareRef(ref.kind, newKey))
    }

    override suspend fun stateOf(ref: ShareRef): LocalShareState {
        val me = dao.getConfig()?.uid
        val link = dao.getLink(ref.kind.name, ref.localKey)?.toDomain()
        val hash = if (link?.isMineShared(me) == true) payloadOf(ref)?.let(SharePlanner::payloadHash) else null
        return SharePlanner.localState(link, hash, me)
    }

    // --- content access -----------------------------------------------------------------------------

    private suspend fun currentLinks(): List<ShareLink> = dao.getLinks().mapNotNull { it.toDomain() }

    private suspend fun linksByShareId(): Map<String, ShareLink> =
        currentLinks().filter { it.shareId != null }.associateBy { it.shareId!! }

    private suspend fun exists(ref: ShareRef): Boolean = nameOf(ref) != null

    private suspend fun nameOf(ref: ShareRef): String? = when (ref.kind) {
        ShareKind.EXERCISE -> exercises.getLibrary().userExercise(ref.localKey)?.exercise?.name
        ShareKind.INGREDIENT -> ref.localKey.toLongOrNull()?.let { ingredients.getById(it) }?.name
        ShareKind.MEAL -> ref.localKey.toLongOrNull()?.let { meals.getTemplate(it) }?.name
        ShareKind.WORKOUT -> ref.localKey.toLongOrNull()?.let { workouts.getById(it) }?.takeIf { !it.isBuiltIn }?.name
    }

    private suspend fun dependenciesOf(ref: ShareRef): List<ShareRef> = when (ref.kind) {
        ShareKind.MEAL -> ref.localKey.toLongOrNull()?.let { meals.getTemplate(it) }?.items
            ?.map { ShareRef(ShareKind.INGREDIENT, it.ingredientId.toString()) }.orEmpty().distinct()
        ShareKind.WORKOUT -> ref.localKey.toLongOrNull()?.let { workouts.getById(it) }?.exercises
            ?.map { it.exerciseId }?.filter { it.startsWith(Exercise.USER_EXERCISE_PREFIX) }
            ?.map { ShareRef(ShareKind.EXERCISE, it) }.orEmpty().distinct()
        ShareKind.EXERCISE, ShareKind.INGREDIENT -> emptyList()
    }

    /** The payload [ref] would be shared with now. Dependencies not yet in the library name a local placeholder. */
    private suspend fun payloadOf(ref: ShareRef): String? {
        val byRef = currentLinks().associateBy { it.ref }
        fun shareIdOf(kind: ShareKind, key: String) = byRef[ShareRef(kind, key)]?.shareId ?: "local:$key"
        return when (ref.kind) {
            ShareKind.EXERCISE -> exercises.getLibrary().userExercise(ref.localKey)?.let(SharePayloads::exercise)
            ShareKind.INGREDIENT -> ref.localKey.toLongOrNull()?.let { ingredients.getById(it) }?.let(SharePayloads::ingredient)
            ShareKind.MEAL -> ref.localKey.toLongOrNull()?.let { meals.getTemplate(it) }
                ?.let { t -> SharePayloads.meal(t) { shareIdOf(ShareKind.INGREDIENT, it.toString()) } }
            ShareKind.WORKOUT -> ref.localKey.toLongOrNull()?.let { workouts.getById(it) }?.takeIf { !it.isBuiltIn }
                ?.let { w -> SharePayloads.workout(w) { shareIdOf(ShareKind.EXERCISE, it) } }
        }
    }

    // --- session ------------------------------------------------------------------------------------

    /** Runs [block] with a valid token; a 401 refreshes once and retries. A new refresh token is stored. */
    private suspend fun <T> withSession(config: ShareConfigEntity, block: suspend (FirestoreClient, FirebaseSession) -> Outcome<T>): Outcome<T> {
        val client = clientFor(config.projectId, config.apiKey)
        val current = session?.takeIf { it.uid == config.uid && it.expiresAtMillis - EXPIRY_MARGIN_MS > clock.millis() }
        val s = current ?: when (val r = renew(client, config)) {
            is Outcome.Err -> return r
            is Outcome.Ok -> r.value
        }
        val first = block(client, s)
        if ((first as? Outcome.Err)?.error.let { it is AppError.Network && it.code == UNAUTHORIZED }) {
            val again = when (val r = renew(client, config)) {
                is Outcome.Err -> return r
                is Outcome.Ok -> r.value
            }
            return block(client, again)
        }
        return first
    }

    private suspend fun renew(client: FirestoreClient, config: ShareConfigEntity): Outcome<FirebaseSession> {
        val r = client.refresh(dao.getConfig()?.refreshToken ?: config.refreshToken)
        if (r is Outcome.Ok) {
            session = r.value
            val stored = dao.getConfig()
            if (stored != null && stored.refreshToken != r.value.refreshToken) {
                dao.upsertConfig(stored.copy(refreshToken = r.value.refreshToken))
            }
        }
        return r
    }

    private fun <T> notConnected(): Outcome<T> = Outcome.Err(AppError.Validation("library", "Not connected to a shared library."))

    private fun <T> missing(): Outcome<T> = Outcome.Err(AppError.Validation("item", "This item no longer exists."))

    private fun unreadable(item: SharedItem): Outcome<Unit> =
        Outcome.Err(AppError.Parse("shared-item", "\"${item.name}\" could not be read. Update the app and try again."))

    private inline fun <T> Outcome<T>.orReturn(onErr: (Outcome.Err) -> Nothing): T = when (this) {
        is Outcome.Ok -> value
        is Outcome.Err -> onErr(this)
    }

    companion object {
        const val MAX_NAME: Int = 40
        private const val UNAUTHORIZED = 401
        private const val NOT_FOUND = 404
        private const val EXPIRY_MARGIN_MS = 60_000L
    }
}
