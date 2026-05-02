package com.seanshubin.vote.tools.lib

import com.seanshubin.vote.domain.DomainEvent
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * One line in a backup/restore JSONL file. The file is a *narrative* of history:
 * line position implicitly defines event_id on restore, so reordering or deleting
 * lines authors a different past. whenHappened is preserved from the original
 * event but is not used for ordering — surprising timestamps after a reorder are
 * the intended tell that the history was edited.
 */
@Serializable
data class NarrativeEvent(
    val whenHappened: Instant,
    val authority: String,
    val event: DomainEvent,
)
