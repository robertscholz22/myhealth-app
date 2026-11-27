package com.myhealth.domain.share

import com.myhealth.domain.share.SharePlanner.ImportStep
import com.myhealth.domain.share.ShareFixtures as F
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P27: the authorship rules. A pull never writes to a row that is mine. */
class SharePlannerTest {

    private fun byShare(vararg links: ShareLink) = links.associateBy { it.shareId!! }

    // --- status -------------------------------------------------------------------------------

    @Test
    fun sp01_my_own_item_is_yours_even_when_not_linked_locally() {
        assertEquals(RemoteStatus.YOURS, SharePlanner.status(F.ingredientItem("i", owner = F.ME), emptyMap(), F.ME))
    }

    @Test
    fun sp02_unknown_item_is_not_imported() {
        assertEquals(RemoteStatus.NOT_IMPORTED, SharePlanner.status(F.ingredientItem("i"), emptyMap(), F.ME))
    }

    @Test
    fun sp03_imported_item_is_up_to_date_or_has_an_update() {
        val link = byShare(F.theirs(ShareKind.INGREDIENT, "3", "i", version = 2))
        assertEquals(RemoteStatus.UP_TO_DATE, SharePlanner.status(F.ingredientItem("i", version = 2), link, F.ME))
        assertEquals(RemoteStatus.UPDATE_AVAILABLE, SharePlanner.status(F.ingredientItem("i", version = 3), link, F.ME))
    }

    @Test
    fun sp04_a_link_that_is_mine_stays_yours_whoever_the_document_names() {
        // A forged or confused document claiming another owner can never make my row "theirs".
        val link = byShare(F.mine(ShareKind.INGREDIENT, "3", "i"))
        assertEquals(RemoteStatus.YOURS, SharePlanner.status(F.ingredientItem("i", version = 9), link, F.ME))
    }

    @Test
    fun sp05_before_joining_every_author_is_someone_else() {
        assertEquals(RemoteStatus.NOT_IMPORTED, SharePlanner.status(F.ingredientItem("i", owner = F.ME), emptyMap(), null))
    }

    // --- local state --------------------------------------------------------------------------

    @Test
    fun sp06_local_states() {
        assertEquals(LocalShareState.Private, SharePlanner.localState(null, "h1", F.ME))
        assertEquals(LocalShareState.Shared(false), SharePlanner.localState(F.mine(ShareKind.MEAL, "5", "m"), "h1", F.ME))
        assertEquals(LocalShareState.Shared(true), SharePlanner.localState(F.mine(ShareKind.MEAL, "5", "m"), "h2", F.ME))
        assertEquals(LocalShareState.Theirs("Anna", false), SharePlanner.localState(F.theirs(ShareKind.MEAL, "5", "m"), "h2", F.ME))
        assertEquals(
            LocalShareState.Theirs("Anna", true),
            SharePlanner.localState(F.theirs(ShareKind.MEAL, "5", "m").copy(remoteGone = true), "h2", F.ME),
        )
    }

    @Test
    fun sp07_fork_state_credits_the_original_and_may_be_shared_again() {
        val fork = SharePlanner.forkLink(F.theirs(ShareKind.MEAL, "5", "m"), "Overnight oats", "9")
        assertEquals(LocalShareState.Fork("Overnight oats", "Anna", null), SharePlanner.localState(fork, "h", F.ME))
        val shared = SharePlanner.pushedLink(fork, fork.ref, "m2", F.ME, "Me", 1, F.T, payload = "{}")
        assertEquals(
            LocalShareState.Fork("Overnight oats", "Anna", LocalShareState.Shared(false)),
            SharePlanner.localState(shared, SharePlanner.payloadHash("{}"), F.ME),
        )
    }

    @Test
    fun sp08_only_theirs_is_read_only() {
        assertTrue(SharePlanner.isReadOnly(F.theirs(ShareKind.EXERCISE, "USER_A", "e"), F.ME))
        assertFalse(SharePlanner.isReadOnly(F.mine(ShareKind.EXERCISE, "USER_A", "e"), F.ME))
        assertFalse(SharePlanner.isReadOnly(null, F.ME))
        assertFalse(SharePlanner.isReadOnly(SharePlanner.forkLink(F.theirs(ShareKind.EXERCISE, "USER_A", "e"), "x", "USER_B"), F.ME))
    }

    @Test
    fun sp09_two_authors_with_the_same_name_are_told_apart_by_id() {
        val other = F.theirs(ShareKind.MEAL, "5", "m", author = "Me", authorId = "uid-other-me")
        assertTrue(other.isTheirs(F.ME))
        assertEquals(LocalShareState.Theirs("Me", false), SharePlanner.localState(other, null, F.ME))
    }

    // --- push ---------------------------------------------------------------------------------

    private val meal = ShareRef(ShareKind.MEAL, "5")
    private val oats = ShareRef(ShareKind.INGREDIENT, "1")
    private val milk = ShareRef(ShareKind.INGREDIENT, "2")
    private val deps: (ShareRef) -> List<ShareRef> = { if (it == meal) listOf(oats, milk) else emptyList() }

    @Test
    fun sp10_push_uploads_my_dependencies_first() {
        assertEquals(listOf(oats, milk, meal), SharePlanner.pushClosure(meal, deps, emptyMap(), F.ME))
    }

    @Test
    fun sp11_push_skips_dependencies_that_are_theirs() {
        val links = mapOf(milk to F.theirs(ShareKind.INGREDIENT, "2", "s-milk"))
        assertEquals(listOf(oats, meal), SharePlanner.pushClosure(meal, deps, links, F.ME))
    }

    @Test
    fun sp12_sharing_someone_elses_row_is_refused() {
        val links = mapOf(meal to F.theirs(ShareKind.MEAL, "5", "m"))
        assertEquals(emptyList<ShareRef>(), SharePlanner.pushClosure(meal, deps, links, F.ME))
    }

    @Test
    fun sp13_push_visits_a_shared_dependency_once() {
        val a = ShareRef(ShareKind.WORKOUT, "1")
        val b = ShareRef(ShareKind.WORKOUT, "2")
        val e = ShareRef(ShareKind.EXERCISE, "USER_E")
        val d: (ShareRef) -> List<ShareRef> = { if (it == a) listOf(e, e, b) else if (it == b) listOf(e) else emptyList() }
        assertEquals(listOf(e, b, a), SharePlanner.pushClosure(a, d, emptyMap(), F.ME))
    }

    @Test
    fun sp14_changed_mine_lists_only_my_shared_rows_whose_content_changed() {
        val links = listOf(
            F.mine(ShareKind.MEAL, "5", "m", hash = "h1"),
            F.mine(ShareKind.MEAL, "6", "n", hash = "h1"),
            F.theirs(ShareKind.MEAL, "7", "o"),
            ShareLink(ShareKind.MEAL, "8", basedOnShareId = "o", basedOnName = "x", basedOnAuthor = "Anna"),
        )
        val now = mapOf("5" to "h2", "6" to "h1", "7" to "h2", "8" to "h2")
        assertEquals(listOf(ShareRef(ShareKind.MEAL, "5")), SharePlanner.changedMine(links, { now[it.localKey] }, F.ME))
    }

    @Test
    fun sp28_payload_hash_is_stable_and_sensitive() {
        assertEquals(SharePlanner.payloadHash("{\"a\":1}"), SharePlanner.payloadHash("{\"a\":1}"))
        assertFalse(SharePlanner.payloadHash("{\"a\":1}") == SharePlanner.payloadHash("{\"a\":2}"))
        assertEquals("cbf29ce484222325", SharePlanner.payloadHash(""))
    }

    // --- import -------------------------------------------------------------------------------

    @Test
    fun sp15_import_brings_its_ingredients_first() {
        val remote = listOf(F.ingredientItem("a"), F.ingredientItem("b"), F.mealItem("m", listOf("a", "b"))).associateBy { it.shareId }
        val plan = SharePlanner.planImport(listOf("m"), remote, emptyMap(), F.ME)
        assertEquals(listOf("a", "b", "m"), plan.steps.map { it.item.shareId })
        assertTrue(plan.steps.all { it is ImportStep.Insert })
        assertTrue(plan.problems.isEmpty())
    }

    @Test
    fun sp16_already_imported_dependencies_are_not_imported_again() {
        val remote = listOf(F.ingredientItem("a"), F.ingredientItem("b"), F.mealItem("m", listOf("a", "b"))).associateBy { it.shareId }
        val plan = SharePlanner.planImport(listOf("m"), remote, byShare(F.theirs(ShareKind.INGREDIENT, "3", "a")), F.ME)
        assertEquals(listOf("b", "m"), plan.steps.map { it.item.shareId })
    }

    @Test
    fun sp17_my_own_ingredient_in_someone_elses_recipe_is_referenced_never_replaced() {
        val remote = listOf(F.ingredientItem("a", owner = F.ME, version = 7), F.mealItem("m", listOf("a"))).associateBy { it.shareId }
        val plan = SharePlanner.planImport(listOf("m"), remote, byShare(F.mine(ShareKind.INGREDIENT, "1", "a", version = 1)), F.ME)
        assertEquals(listOf("m"), plan.steps.map { it.item.shareId })
    }

    @Test
    fun sp18_update_replaces_only_the_imported_row() {
        val remote = listOf(F.ingredientItem("a", version = 2)).associateBy { it.shareId }
        val plan = SharePlanner.planImport(listOf("a"), remote, byShare(F.theirs(ShareKind.INGREDIENT, "3", "a")), F.ME)
        assertEquals(listOf(ImportStep.Update(remote.getValue("a"), "3")), plan.steps)
    }

    @Test
    fun sp19_importing_my_own_item_does_nothing() {
        val remote = listOf(F.ingredientItem("a", owner = F.ME, version = 5)).associateBy { it.shareId }
        assertTrue(SharePlanner.planImport(listOf("a"), remote, emptyMap(), F.ME).steps.isEmpty())
        assertTrue(SharePlanner.planImport(listOf("a"), remote, byShare(F.mine(ShareKind.INGREDIENT, "1", "a")), F.ME).steps.isEmpty())
    }

    @Test
    fun sp20_a_barcode_the_device_already_has_reuses_that_row() {
        val remote = listOf(F.ingredientItem("a", barcode = "4000000000001"), F.mealItem("m", listOf("a"))).associateBy { it.shareId }
        val plan = SharePlanner.planImport(listOf("m"), remote, emptyMap(), F.ME) { if (it == "4000000000001") "1" else null }
        assertEquals(ImportStep.Insert(remote.getValue("a"), reuseLocalKey = "1"), plan.steps.first())
    }

    @Test
    fun sp21_a_missing_dependency_is_reported_and_the_item_left_out() {
        val remote = listOf(F.ingredientItem("a"), F.mealItem("m", listOf("a", "gone"))).associateBy { it.shareId }
        val plan = SharePlanner.planImport(listOf("m", "a"), remote, emptyMap(), F.ME)
        assertEquals(listOf("a"), plan.steps.map { it.item.shareId })
        assertEquals(listOf(SharePlanner.ImportProblem.MissingDependency("m", "gone")), plan.problems)
    }

    @Test
    fun sp22_a_dependency_gone_from_the_library_but_on_the_device_is_fine() {
        val remote = listOf(F.mealItem("m", listOf("gone"))).associateBy { it.shareId }
        val plan = SharePlanner.planImport(listOf("m"), remote, byShare(F.theirs(ShareKind.INGREDIENT, "3", "gone")), F.ME)
        assertEquals(listOf("m"), plan.steps.map { it.item.shareId })
        assertTrue(plan.problems.isEmpty())
    }

    @Test
    fun sp23_workout_import_brings_its_own_exercise() {
        val ex = F.item("e", ShareKind.EXERCISE, SharePayloads.exercise(F.exercise()))
        val w = F.item("w", ShareKind.WORKOUT, SharePayloads.workout(F.workout()) { "e" })
        val plan = SharePlanner.planImport(listOf("w"), listOf(ex, w).associateBy { it.shareId }, emptyMap(), F.ME)
        assertEquals(listOf("e", "w"), plan.steps.map { it.item.shareId })
    }

    @Test
    fun sp24_unknown_share_ids_are_ignored() {
        assertTrue(SharePlanner.planImport(listOf("nope"), emptyMap(), emptyMap(), F.ME).steps.isEmpty())
    }

    @Test
    fun sp25_updatable_and_gone() {
        val links = listOf(
            F.theirs(ShareKind.INGREDIENT, "3", "a", version = 1),
            F.theirs(ShareKind.INGREDIENT, "4", "b", version = 1),
            F.theirs(ShareKind.INGREDIENT, "5", "c", version = 1),
            F.mine(ShareKind.INGREDIENT, "6", "d"),
        )
        val remote = listOf(F.ingredientItem("a", version = 2), F.ingredientItem("b", version = 1))
        assertEquals(listOf("a"), SharePlanner.updatable(remote, links.associateBy { it.shareId!! }, F.ME))
        // "c" is theirs and gone; "d" is mine and never marked, even though it is missing remotely.
        assertEquals(listOf("c"), SharePlanner.gone(links, setOf("a", "b"), F.ME).map { it.shareId })
    }

    @Test
    fun sp26_fork_link_clears_the_author_and_keeps_the_credit() {
        val fork = SharePlanner.forkLink(F.theirs(ShareKind.WORKOUT, "7", "w"), "Glute video", "8")
        assertEquals(ShareLink(ShareKind.WORKOUT, "8", basedOnShareId = "w", basedOnName = "Glute video", basedOnAuthor = "Anna"), fork)
        assertFalse(fork.isTheirs(F.ME))
    }

    @Test
    fun sp27_imported_link_records_author_and_version() {
        val link = SharePlanner.importedLink(F.ingredientItem("a", version = 4), "12", F.T)
        assertEquals(F.ANNA, link.authorId)
        assertEquals("Anna", link.authorName)
        assertEquals(4L, link.version)
        assertTrue(link.isTheirs(F.ME))
    }
}
