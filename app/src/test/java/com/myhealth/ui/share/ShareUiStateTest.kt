package com.myhealth.ui.share

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.repository.LibrarySnapshot
import com.myhealth.domain.repository.ShareSetup
import com.myhealth.domain.share.RemoteStatus
import com.myhealth.domain.share.ShareFixtures as F
import com.myhealth.domain.share.ShareKind
import org.junit.Test

/** P27.6: what the library list shows for a filter, an author and a search. */
class ShareUiStateTest {

    private val me = "uid-me"
    private val state = ShareUiState(
        setup = ShareSetup("demo-family", "k", "j", me, "Me"),
        library = LibrarySnapshot(
            items = listOf(
                F.item("a", ShareKind.MEAL, "{}", name = "Berry bowl"),
                F.item("b", ShareKind.INGREDIENT, "{}", name = "Skyr"),
                F.item("c", ShareKind.MEAL, "{}", owner = me, author = "Me", name = "Porridge"),
                F.item("d", ShareKind.WORKOUT, "{}", owner = "uid-ben", author = "Ben", name = "Core"),
            ),
            members = emptyList(),
            fetchedAtMillis = 0,
        ),
        links = listOf(F.theirs(ShareKind.MEAL, "5", "a", version = 0)),
    )

    @Test
    fun rows_are_sorted_by_type_then_name_with_their_status() {
        assertThat(state.rows.map { it.item.name }).containsExactly("Skyr", "Berry bowl", "Porridge", "Core").inOrder()
        assertThat(state.rows.map { it.status }).containsExactly(
            RemoteStatus.NOT_IMPORTED, RemoteStatus.UPDATE_AVAILABLE, RemoteStatus.YOURS, RemoteStatus.NOT_IMPORTED,
        ).inOrder()
        assertThat(state.updatableCount).isEqualTo(1)
    }

    @Test
    fun type_author_and_search_filters_combine() {
        assertThat(state.copy(filter = ShareKind.MEAL).rows.map { it.item.name }).containsExactly("Berry bowl", "Porridge")
        assertThat(state.copy(author = F.ANNA).rows.map { it.item.name }).containsExactly("Skyr", "Berry bowl")
        assertThat(state.copy(query = " bowl").rows.map { it.item.name }).containsExactly("Berry bowl")
        assertThat(state.copy(filter = ShareKind.WORKOUT, author = F.ANNA).rows).isEmpty()
        // "Update all" counts every item, whatever is filtered.
        assertThat(state.copy(filter = ShareKind.WORKOUT).updatableCount).isEqualTo(1)
    }

    @Test
    fun authors_list_me_first_then_by_name() {
        assertThat(state.authors).containsExactly(me to "Me", F.ANNA to "Anna", "uid-ben" to "Ben").inOrder()
    }

    @Test
    fun a_creator_filter_for_someone_no_longer_listed_shows_everything() {
        // Found in the walkthrough: "Me" chosen, then leave and join again with a new uid; the old
        // uid matched nothing and, with one author left, there were no chips to clear it.
        val stale = state.copy(author = "uid-old-me")
        assertThat(stale.activeAuthor).isNull()
        assertThat(stale.rows).hasSize(4)
        val onlyAnna = state.copy(
            author = F.ANNA,
            library = state.library!!.copy(items = state.library!!.items.filter { it.ownerUid == F.ANNA }),
        )
        assertThat(onlyAnna.activeAuthor).isNull()
        assertThat(onlyAnna.rows).hasSize(2)
    }
}
