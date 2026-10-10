package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.contract.ApiClient
import com.seanshubin.vote.domain.CandidateNote
import com.seanshubin.vote.domain.ReviewLink
import kotlinx.browser.window
import org.jetbrains.compose.web.dom.*

/**
 * Free-form per-candidate reviews attached by voters.
 *
 * Read-dominant by default. Every candidate's reviews are shown at once as
 * read-only cards, with a sticky header per candidate so the reader always
 * knows whose reviews they're scrolling through. The current voter's review
 * renders as just another read-only card alongside the others — distinguished
 * only by a "(you)" suffix — so the browsing surface is uniform.
 *
 * A page-level "Edit my reviews" toggle flips the section into edit mode.
 * In edit mode the current voter's card per candidate morphs into a textarea
 * + Save (per-candidate save granularity); candidates the voter hasn't
 * reviewed yet show an empty editor so the mode itself is the invitation to
 * write. Other voters' cards stay read-only. Clicking "Done" flips back.
 *
 * Errors are presented *inside* this section rather than handed to a
 * page-level banner. A per-candidate Save sits arbitrarily far down a long
 * page (one block per candidate), so a banner at the top of the page is
 * off-screen exactly when it matters and leaves the voter staring at a
 * button that looks like it did nothing. Keeping the reason next to the
 * control that failed also means a successful retry can't leave a stale
 * failure behind somewhere the voter isn't looking.
 */
@Composable
fun CandidateNotesSection(
    apiClient: ApiClient,
    electionName: String,
    candidates: List<String>,
    currentUserName: String?,
) {
    if (candidates.isEmpty()) return

    var notesByCandidate by remember(electionName, candidates) {
        mutableStateOf<Map<String, List<CandidateNote>>?>(null)
    }
    var editing by remember(electionName) { mutableStateOf(false) }
    var loadError by remember(electionName, candidates) { mutableStateOf<String?>(null) }

    LaunchedEffect(electionName, candidates) {
        notesByCandidate = try {
            loadError = null
            apiClient.listCandidateNotesByElection(electionName)
                .groupBy { it.candidateName }
        } catch (e: Exception) {
            apiClient.logErrorToServer(e)
            // An empty map here is indistinguishable from "nobody has
            // reviewed anything yet", so the voter would be told a fetch
            // failure is an empty election. Record the reason instead.
            loadError = e.message ?: "Failed to load reviews"
            emptyMap()
        }
    }

    Div({ classes("section") }) {
        H2 { Text("Candidate reviews") }
        P({ classes("candidate-notes-intro") }) {
            Text(
                "Browse every voter's free-form review of each candidate. " +
                    "Click \"Edit my reviews\" to write or update your own. " +
                    // Worth saying now that formatting is meaningful: a
                    // reviewer typing # or - should know it will render, and
                    // one expecting a link should know it will not.
                    "Formatting: # headings, - bullets, 1. numbered lists, " +
                    "**bold**, *italic*. Links and HTML are not supported."
            )
        }

        val loaded = notesByCandidate
        if (loaded == null) {
            P { Text("Loading reviews…") }
            return@Div
        }

        if (loadError != null) {
            Div({ classes("candidate-notes-load-error") }) { Text(loadError!!) }
            return@Div
        }

        if (currentUserName != null) {
            Div({ classes("candidate-notes-toolbar") }) {
                Button({
                    classes("candidate-notes-toolbar-button")
                    onClick { editing = !editing }
                }) {
                    Text(if (editing) "Done" else "Edit my reviews")
                }
            }
        }

        // Sort candidates alphabetically so the section order is stable and
        // independent of the ranked-ballot ordering above. Case-insensitive
        // to match the app's name-handling convention everywhere else.
        val ordered = candidates.sortedWith(String.CASE_INSENSITIVE_ORDER)
        // View mode is read-dominant — hide candidates with zero reviews so
        // the reader isn't scrolling past empty blocks. Edit mode keeps every
        // candidate visible so the voter can add a review for any of them.
        val visible = if (editing) ordered else ordered.filter { (loaded[it] ?: emptyList()).isNotEmpty() }
        if (visible.isEmpty()) {
            P({ classes("candidate-notes-empty-hint") }) {
                Text(
                    if (currentUserName != null) "No reviews yet. Click \"Edit my reviews\" to write one."
                    else "No reviews yet."
                )
            }
            return@Div
        }
        Div({ classes("candidate-notes-sections") }) {
            visible.forEach { candidateName ->
                val notes = loaded[candidateName] ?: emptyList()
                CandidateNotesBlock(
                    apiClient = apiClient,
                    electionName = electionName,
                    candidateName = candidateName,
                    notes = notes,
                    currentUserName = currentUserName,
                    editing = editing,
                    onNoteSaved = { newText ->
                        notesByCandidate = patchOwnNote(
                            loaded = notesByCandidate ?: emptyMap(),
                            candidateName = candidateName,
                            voterName = currentUserName,
                            text = newText,
                        )
                    },
                )
            }
        }
    }
}

/**
 * Replace (or insert, or remove on empty) the current voter's note for one
 * candidate in the local cache. Pure function — the caller threads the
 * returned map into state.
 */
private fun patchOwnNote(
    loaded: Map<String, List<CandidateNote>>,
    candidateName: String,
    voterName: String?,
    text: String,
): Map<String, List<CandidateNote>> {
    if (voterName == null) return loaded
    val existing = loaded[candidateName] ?: emptyList()
    val withoutMine = existing.filter { !it.voterName.equals(voterName, ignoreCase = true) }
    val updated = if (text.isBlank()) {
        withoutMine
    } else {
        val mine = CandidateNote(
            electionName = existing.firstOrNull()?.electionName ?: "",
            candidateName = candidateName,
            voterName = voterName,
            text = text.trim(),
            lastUpdated = kotlinx.datetime.Clock.System.now(),
        )
        listOf(mine) + withoutMine
    }
    return loaded + (candidateName to updated)
}

@Composable
private fun CandidateNotesBlock(
    apiClient: ApiClient,
    electionName: String,
    candidateName: String,
    notes: List<CandidateNote>,
    currentUserName: String?,
    editing: Boolean,
    onNoteSaved: (String) -> Unit,
) {
    val myNote = currentUserName?.let { voter ->
        notes.firstOrNull { it.voterName.equals(voter, ignoreCase = true) }
    }
    val others = notes.filter { note ->
        currentUserName == null || !note.voterName.equals(currentUserName, ignoreCase = true)
    }

    Div({ classes("candidate-notes-block") }) {
        Div({ classes("candidate-notes-block-header") }) {
            Span({ classes("candidate-notes-block-name") }) { Text(candidateName) }
            Span({ classes("candidate-notes-block-count") }) {
                Text(
                    when (notes.size) {
                        0 -> "no reviews"
                        1 -> "1 review"
                        else -> "${notes.size} reviews"
                    }
                )
            }
        }

        Div({ classes("candidate-notes-block-body") }) {
            if (editing && currentUserName != null) {
                MyNoteEditor(
                    apiClient = apiClient,
                    electionName = electionName,
                    candidateName = candidateName,
                    currentUserName = currentUserName,
                    existing = myNote,
                    onSaved = onNoteSaved,
                )
            } else if (myNote != null) {
                ReadOnlyNoteCard(myNote, isMine = true, electionName = electionName, apiClient = apiClient)
            }

            others.forEach { note ->
                ReadOnlyNoteCard(note, isMine = false, electionName = electionName, apiClient = apiClient)
            }
        }
    }
}

@Composable
private fun MyNoteEditor(
    apiClient: ApiClient,
    electionName: String,
    candidateName: String,
    currentUserName: String,
    existing: CandidateNote?,
    onSaved: (String) -> Unit,
) {
    var draft by remember(electionName, candidateName, existing?.text) {
        mutableStateOf(existing?.text ?: "")
    }
    var savedFeedback by remember { mutableStateOf<String?>(null) }
    var saveError by remember(electionName, candidateName) { mutableStateOf<String?>(null) }

    // The server trims before it counts, so count the trimmed text here or
    // the readout would disagree with the rule it is reporting on.
    val trimmedDraft = draft.trim()
    val storedText = existing?.text?.trim() ?: ""
    val dirty = trimmedDraft != storedText
    val overBy = trimmedDraft.length - CandidateNote.MAX_TEXT_LENGTH

    val saveAction = rememberAsyncAction(
        apiClient = apiClient,
        fallbackErrorMessage = "Failed to save review",
        onError = { message -> saveError = message },
        action = {
            // Drop a previous reason as the retry starts, so the card never
            // shows a stale failure beside an in-flight save.
            saveError = null
            apiClient.setCandidateNote(electionName, candidateName, trimmedDraft)
            onSaved(trimmedDraft)
            savedFeedback = if (trimmedDraft.isEmpty()) "Cleared" else "Saved"
        },
    )

    LaunchedEffect(savedFeedback) {
        if (savedFeedback != null) {
            kotlinx.coroutines.delay(1500)
            savedFeedback = null
        }
    }

    Div({ classes("candidate-notes-card", "candidate-notes-card-mine") }) {
        Div({ classes("candidate-notes-card-header") }) {
            Span({ classes("candidate-notes-card-author") }) {
                Text("$currentUserName (you)")
            }
            if (existing != null) {
                Span({ classes("candidate-notes-card-timestamp") }) {
                    Text("updated ${formatWhenCast(existing.lastUpdated)}")
                }
            }
        }
        TextArea(draft) {
            classes("candidate-notes-card-textarea")
            attr("rows", "4")
            attr("placeholder", "Write a review about this candidate…")
            onInput { event ->
                draft = event.value
                // The reason can quote specifics about the text that just
                // changed (e.g. a character count), so it stops being true
                // as soon as the voter edits. Retract it on the first
                // keystroke rather than let a wrong number sit there.
                saveError = null
            }
            if (saveAction.isLoading) attr("disabled", "")
        }
        Div({
            classes("candidate-notes-card-counter")
            if (overBy > 0) classes("candidate-notes-card-counter-over")
        }) {
            Text("${withThousands(trimmedDraft.length)} / ${withThousands(CandidateNote.MAX_TEXT_LENGTH)}")
        }
        if (overBy > 0) {
            // Said before the voter presses Save, and phrased as the amount
            // to cut, which is the number they actually need. The server
            // would reject this too, but finding out on Save means writing
            // into a limit you cannot see.
            Div({ classes("candidate-notes-card-error") }) {
                Text("${withThousands(overBy)} characters over the limit — trim to save.")
            }
        }
        Div({ classes("candidate-notes-card-actions") }) {
            Button({
                classes("candidate-notes-card-save")
                if (!dirty || saveAction.isLoading || overBy > 0) attr("disabled", "")
                onClick { saveAction.invoke() }
            }) {
                Text(if (saveAction.isLoading) "Saving…" else "Save")
            }
            if (existing != null && trimmedDraft.isNotEmpty()) {
                Button({
                    classes("candidate-notes-card-clear")
                    if (saveAction.isLoading) attr("disabled", "")
                    onClick {
                        // "Delete" path: clear the draft. The Save button
                        // then sends empty text, which the server interprets
                        // as "delete my note."
                        draft = ""
                    }
                }) { Text("Clear") }
            }
            if (savedFeedback != null) {
                Span({ classes("candidate-notes-card-feedback") }) { Text(savedFeedback!!) }
            }
        }
        if (saveError != null) {
            Div({ classes("candidate-notes-card-error") }) { Text(saveError!!) }
        }
    }
}

private fun withThousands(value: Int): String {
    val digits = value.toString()
    return digits.reversed().chunked(3).joinToString(",").reversed()
}

@Composable
private fun ReadOnlyNoteCard(
    note: CandidateNote,
    isMine: Boolean,
    electionName: String,
    apiClient: ApiClient,
) {
    var copied by remember(note.candidateName, note.voterName) { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1500)
            copied = false
        }
    }

    Div({ classes("candidate-notes-card", "candidate-notes-card-other") }) {
        Div({ classes("candidate-notes-card-header") }) {
            Span({ classes("candidate-notes-card-author") }) {
                Text(if (isMine) "${note.voterName} (you)" else note.voterName)
            }
            Span({ classes("candidate-notes-card-timestamp") }) {
                Text(formatWhenCast(note.lastUpdated))
            }
            Button({
                classes("candidate-notes-card-share")
                attr("title", "Copy a link to this review that anyone can open")
                onClick {
                    // Built from the election name passed down the tree, not
                    // note.electionName: a note patched in locally right
                    // after saving the first review for a candidate carries
                    // an empty one, which would make a link that resolves to
                    // nothing.
                    val path = ReviewLink.path(
                        electionName = electionName,
                        candidateName = note.candidateName,
                        reviewerName = note.voterName,
                    )
                    copyTextToClipboard(window.location.origin + path, apiClient)
                    copied = true
                }
            }) {
                Text(if (copied) "Link copied" else "Copy link")
            }
        }
        // Rendered as the same small markdown dialect the shared review
        // page uses, from the same parser in :domain, so a review that
        // shows a heading or a list in a shared link shows one here too.
        Div({ classes("candidate-notes-card-text") }) {
            MarkdownText(note.text)
        }
    }
}
