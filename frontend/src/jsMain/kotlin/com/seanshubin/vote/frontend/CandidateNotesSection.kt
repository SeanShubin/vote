package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.contract.ApiClient
import com.seanshubin.vote.domain.CandidateNote
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
 */
@Composable
fun CandidateNotesSection(
    apiClient: ApiClient,
    electionName: String,
    candidates: List<String>,
    currentUserName: String?,
    onError: (String) -> Unit,
) {
    if (candidates.isEmpty()) return

    var notesByCandidate by remember(electionName, candidates) {
        mutableStateOf<Map<String, List<CandidateNote>>?>(null)
    }
    var editing by remember(electionName) { mutableStateOf(false) }

    LaunchedEffect(electionName, candidates) {
        val collected = mutableMapOf<String, List<CandidateNote>>()
        for (name in candidates) {
            try {
                collected[name] = apiClient.listCandidateNotes(electionName, name)
            } catch (e: Exception) {
                apiClient.logErrorToServer(e)
                collected[name] = emptyList()
            }
        }
        notesByCandidate = collected
    }

    Div({ classes("section") }) {
        H2 { Text("Candidate reviews") }
        P({ classes("candidate-notes-intro") }) {
            Text(
                "Browse every voter's free-form review of each candidate. " +
                    "Click \"Edit my reviews\" to write or update your own."
            )
        }

        val loaded = notesByCandidate
        if (loaded == null) {
            P { Text("Loading reviews…") }
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
                    onError = onError,
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
    onError: (String) -> Unit,
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
                    onError = onError,
                )
            } else if (myNote != null) {
                ReadOnlyNoteCard(myNote, isMine = true)
            }

            others.forEach { note ->
                ReadOnlyNoteCard(note, isMine = false)
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
    onError: (String) -> Unit,
) {
    var draft by remember(electionName, candidateName, existing?.text) {
        mutableStateOf(existing?.text ?: "")
    }
    var savedFeedback by remember { mutableStateOf<String?>(null) }

    val trimmedDraft = draft.trim()
    val storedText = existing?.text?.trim() ?: ""
    val dirty = trimmedDraft != storedText

    val saveAction = rememberAsyncAction(
        apiClient = apiClient,
        fallbackErrorMessage = "Failed to save review",
        onError = onError,
        action = {
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
            onInput { event -> draft = event.value }
            if (saveAction.isLoading) attr("disabled", "")
        }
        Div({ classes("candidate-notes-card-actions") }) {
            Button({
                classes("candidate-notes-card-save")
                if (!dirty || saveAction.isLoading) attr("disabled", "")
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
    }
}

@Composable
private fun ReadOnlyNoteCard(note: CandidateNote, isMine: Boolean) {
    Div({ classes("candidate-notes-card", "candidate-notes-card-other") }) {
        Div({ classes("candidate-notes-card-header") }) {
            Span({ classes("candidate-notes-card-author") }) {
                Text(if (isMine) "${note.voterName} (you)" else note.voterName)
            }
            Span({ classes("candidate-notes-card-timestamp") }) {
                Text(formatWhenCast(note.lastUpdated))
            }
        }
        // Preserve paragraph breaks the voter wrote — the textarea editor
        // sends literal \n, so render with white-space:pre-wrap on the
        // wrapper rather than collapsing into one line.
        Div({ classes("candidate-notes-card-text") }) {
            Text(note.text)
        }
    }
}
