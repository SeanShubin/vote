package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.contract.ApiClient
import com.seanshubin.vote.domain.CandidateNote
import org.jetbrains.compose.web.dom.*

/**
 * Free-form per-candidate notes attached by voters.
 *
 * One row per candidate, collapsed-by-default. The header carries a count
 * badge ("3 notes") so the voter can see what's behind the expand without
 * paying for the lookup. On expand, every note is shown as an
 * author-attributed card; the current voter's card is editable inline,
 * other voters' cards are read-only.
 *
 * One note per (candidate, voter): a voter may freely edit their own note,
 * but every other voter sees the most-recent saved text.
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

    // Per-candidate note state; null = not yet loaded.
    var notesByCandidate by remember(electionName, candidates) {
        mutableStateOf<Map<String, List<CandidateNote>>?>(null)
    }
    var expanded by remember(electionName) { mutableStateOf<Set<String>>(emptySet()) }

    // Fetch every candidate's notes on mount. We could lazy-load per-expand,
    // but the badge needs the count up front and the payload is small —
    // one query per candidate beats running them sequentially on expand.
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
                "Attach a free-form review to any candidate. Everyone can read; " +
                    "only you can edit your own review."
            )
        }
        val loaded = notesByCandidate
        if (loaded == null) {
            P { Text("Loading reviews…") }
            return@Div
        }

        // Sort candidates alphabetically so the section order is stable and
        // independent of the ranked-ballot ordering above. Casing-insensitive
        // to match the app's name-handling convention everywhere else.
        val ordered = candidates.sortedWith(String.CASE_INSENSITIVE_ORDER)
        Div({ classes("candidate-notes-list") }) {
            ordered.forEach { candidateName ->
                val notes = loaded[candidateName] ?: emptyList()
                val isExpanded = candidateName in expanded
                CandidateNotesRow(
                    apiClient = apiClient,
                    electionName = electionName,
                    candidateName = candidateName,
                    notes = notes,
                    currentUserName = currentUserName,
                    isExpanded = isExpanded,
                    onToggle = {
                        expanded = if (isExpanded) expanded - candidateName
                        else expanded + candidateName
                    },
                    onNoteSaved = { newText ->
                        // Patch local state in place so the count badge and the
                        // expanded card update immediately. The server is the
                        // source of truth — a background refetch after the save
                        // would also work but feels slower without buying us
                        // anything for this single-author surface.
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
        // Use a sentinel epoch for the in-memory copy; the real lastUpdated
        // will land on the next refetch. Sorting puts the just-saved note at
        // the top for the optimistic update.
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
private fun CandidateNotesRow(
    apiClient: ApiClient,
    electionName: String,
    candidateName: String,
    notes: List<CandidateNote>,
    currentUserName: String?,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onNoteSaved: (String) -> Unit,
    onError: (String) -> Unit,
) {
    Div({ classes("candidate-notes-row") }) {
        Button({
            classes("candidate-notes-row-header")
            onClick { onToggle() }
            attr(
                "aria-expanded",
                if (isExpanded) "true" else "false",
            )
        }) {
            Span({ classes("candidate-notes-row-toggle") }) {
                Text(if (isExpanded) "▾" else "▸")
            }
            Span({ classes("candidate-notes-row-name") }) { Text(candidateName) }
            Span({ classes("candidate-notes-row-count") }) {
                Text(
                    when (notes.size) {
                        0 -> "no reviews"
                        1 -> "1 review"
                        else -> "${notes.size} reviews"
                    }
                )
            }
        }

        if (isExpanded) {
            Div({ classes("candidate-notes-row-body") }) {
                val myNote = currentUserName?.let { voter ->
                    notes.firstOrNull { it.voterName.equals(voter, ignoreCase = true) }
                }
                val others = notes.filter { note ->
                    currentUserName == null || !note.voterName.equals(currentUserName, ignoreCase = true)
                }

                // Always render the current voter's editor first so their own
                // card is the obvious entry point. Others render below in
                // most-recent-first order (the server already sorts).
                if (currentUserName != null) {
                    MyNoteEditor(
                        apiClient = apiClient,
                        electionName = electionName,
                        candidateName = candidateName,
                        currentUserName = currentUserName,
                        existing = myNote,
                        onSaved = onNoteSaved,
                        onError = onError,
                    )
                }

                if (others.isEmpty() && myNote == null) {
                    P({ classes("candidate-notes-empty-hint") }) {
                        Text("Nobody has attached a review to this candidate yet.")
                    }
                } else {
                    others.forEach { note ->
                        ReadOnlyNoteCard(note)
                    }
                }
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
private fun ReadOnlyNoteCard(note: CandidateNote) {
    Div({ classes("candidate-notes-card", "candidate-notes-card-other") }) {
        Div({ classes("candidate-notes-card-header") }) {
            Span({ classes("candidate-notes-card-author") }) { Text(note.voterName) }
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
