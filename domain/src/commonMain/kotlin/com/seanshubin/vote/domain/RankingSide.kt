package com.seanshubin.vote.domain

import kotlinx.serialization.Serializable

/**
 * Which side of a dual-sided ballot a [Ranking] belongs to.
 *
 * Every ballot has two independent sides — [PUBLIC] and [SECRET] — that
 * coexist under a single confirmation id. A voter may fill in either or
 * both; an empty side means the voter did not cast on that side and the
 * ballot is treated as nonexistent from that side's point of view.
 *
 * The two sides are tallied independently and produce separate results;
 * a voter's public and secret rankings never influence each other.
 *
 * Secrecy: which voter cast which secret-side ballot is restricted to
 * callers with [Permission.VIEW_SECRETS]. The rankings themselves on the
 * secret side are public (so anyone can browse the secret tally), but
 * the voter→ballot mapping is redacted for everyone else.
 *
 * Default is [PUBLIC] so existing event-log entries that pre-date this
 * field still deserialize cleanly as public-side rankings.
 */
@Serializable
enum class RankingSide {
    PUBLIC,
    SECRET,
}
