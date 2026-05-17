package com.seanshubin.vote.tools.commands

import com.seanshubin.vote.domain.DomainEvent
import com.seanshubin.vote.domain.Ranking
import com.seanshubin.vote.domain.Role
import com.seanshubin.vote.tools.lib.NarrativeEvent
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class NormalizeCaseTransformTest {
    private val t0 = Instant.fromEpochMilliseconds(0)

    private fun narratives(vararg events: DomainEvent): List<NarrativeEvent> =
        events.map { NarrativeEvent(whenHappened = t0, authority = "system", event = it) }

    private fun assertOk(result: NormalizeCaseTransform.Result): NormalizeCaseTransform.Result.Ok =
        result as? NormalizeCaseTransform.Result.Ok
            ?: fail("expected Ok, got $result")

    private fun assertCollisions(result: NormalizeCaseTransform.Result): NormalizeCaseTransform.Result.Collisions =
        result as? NormalizeCaseTransform.Result.Collisions
            ?: fail("expected Collisions, got $result")

    @Test
    fun `ballot ranking using case-variant candidate name is rewritten to canonical`() {
        val result = NormalizeCaseTransform.transform(
            narratives(
                DomainEvent.UserRegisteredViaDiscord("alice", "did-alice", "alice", Role.OWNER),
                DomainEvent.ElectionCreated("alice", "Best Movie"),
                DomainEvent.CandidatesAdded("Best Movie", listOf("Dune")),
                DomainEvent.BallotCast(
                    voterName = "alice",
                    electionName = "best movie",
                    rankings = listOf(Ranking("DUNE", 1)),
                    confirmation = "c1",
                    whenCast = t0,
                ),
            )
        )

        val ok = assertOk(result)
        val cast = ok.events.last().event as DomainEvent.BallotCast
        assertEquals("Best Movie", cast.electionName)
        assertEquals(listOf("Dune"), cast.rankings.map { it.candidateName })
        assertTrue(ok.rewrites >= 2)
    }

    @Test
    fun `tier annotation using case-variant tier name is rewritten to canonical`() {
        val result = NormalizeCaseTransform.transform(
            narratives(
                DomainEvent.UserRegisteredViaDiscord("alice", "did-alice", "alice", Role.OWNER),
                DomainEvent.ElectionCreated("alice", "Best Snack"),
                DomainEvent.CandidatesAdded("Best Snack", listOf("Popcorn")),
                DomainEvent.TiersSet("Best Snack", listOf("Gold", "Silver")),
                DomainEvent.BallotCast(
                    voterName = "alice",
                    electionName = "Best Snack",
                    rankings = listOf(Ranking("Popcorn", 1, tier = "gold")),
                    confirmation = "c1",
                    whenCast = t0,
                ),
            )
        )

        val ok = assertOk(result)
        val cast = ok.events.last().event as DomainEvent.BallotCast
        assertEquals("Gold", cast.rankings.single().tier)
    }

    @Test
    fun `CandidatesAdded with case-variant duplicates dedupes silently`() {
        val result = NormalizeCaseTransform.transform(
            narratives(
                DomainEvent.UserRegisteredViaDiscord("alice", "did-alice", "alice", Role.OWNER),
                DomainEvent.ElectionCreated("alice", "Best Color"),
                DomainEvent.CandidatesAdded("Best Color", listOf("Red", "RED", "Blue")),
            )
        )

        val ok = assertOk(result)
        val added = ok.events.last().event as DomainEvent.CandidatesAdded
        assertEquals(listOf("Red", "Blue"), added.candidateNames)
    }

    @Test
    fun `CandidatesAdded across calls dedupes case-variants and preserves first`() {
        val result = NormalizeCaseTransform.transform(
            narratives(
                DomainEvent.UserRegisteredViaDiscord("alice", "did-alice", "alice", Role.OWNER),
                DomainEvent.ElectionCreated("alice", "Best Drink"),
                DomainEvent.CandidatesAdded("Best Drink", listOf("Coffee")),
                DomainEvent.CandidatesAdded("Best Drink", listOf("coffee", "Tea")),
            )
        )

        val ok = assertOk(result)
        val secondAdd = ok.events.last().event as DomainEvent.CandidatesAdded
        // "coffee" was a case-variant of existing "Coffee" — dropped from the
        // event; only "Tea" remains.
        assertEquals(listOf("Tea"), secondAdd.candidateNames)
    }

    @Test
    fun `TiersSet with case-variant duplicates within input dedupes`() {
        val result = NormalizeCaseTransform.transform(
            narratives(
                DomainEvent.UserRegisteredViaDiscord("alice", "did-alice", "alice", Role.OWNER),
                DomainEvent.ElectionCreated("alice", "Best Game"),
                DomainEvent.TiersSet("Best Game", listOf("Gold", "gold", "Silver")),
            )
        )

        val ok = assertOk(result)
        val set = ok.events.last().event as DomainEvent.TiersSet
        assertEquals(listOf("Gold", "Silver"), set.tierNames)
    }

    @Test
    fun `CandidateRenamed updates canonical so later ballots use the new case`() {
        val result = NormalizeCaseTransform.transform(
            narratives(
                DomainEvent.UserRegisteredViaDiscord("alice", "did-alice", "alice", Role.OWNER),
                DomainEvent.ElectionCreated("alice", "Best Book"),
                DomainEvent.CandidatesAdded("Best Book", listOf("Dune")),
                DomainEvent.CandidateRenamed("Best Book", "Dune", "dune"),
                DomainEvent.BallotCast(
                    voterName = "alice",
                    electionName = "Best Book",
                    rankings = listOf(Ranking("Dune", 1)),
                    confirmation = "c1",
                    whenCast = t0,
                ),
            )
        )

        val ok = assertOk(result)
        val cast = ok.events.last().event as DomainEvent.BallotCast
        // After rename, "dune" is the canonical case; the ballot's "Dune"
        // reference is rewritten to it.
        assertEquals("dune", cast.rankings.single().candidateName)
    }

    @Test
    fun `two ElectionCreated with case-variant names is a hard collision`() {
        val result = NormalizeCaseTransform.transform(
            narratives(
                DomainEvent.UserRegisteredViaDiscord("alice", "did-alice", "alice", Role.OWNER),
                DomainEvent.ElectionCreated("alice", "Best Movie"),
                DomainEvent.ElectionCreated("alice", "best movie"),
            )
        )

        val collisions = assertCollisions(result)
        assertTrue(collisions.report.any { it.contains("Best Movie") && it.contains("best movie") })
    }

    @Test
    fun `CandidateRenamed to a different existing candidate is a hard collision`() {
        val result = NormalizeCaseTransform.transform(
            narratives(
                DomainEvent.UserRegisteredViaDiscord("alice", "did-alice", "alice", Role.OWNER),
                DomainEvent.ElectionCreated("alice", "Best Pet"),
                DomainEvent.CandidatesAdded("Best Pet", listOf("Dog", "Cat")),
                DomainEvent.CandidateRenamed("Best Pet", "Dog", "CAT"),
            )
        )

        val collisions = assertCollisions(result)
        assertTrue(collisions.report.any { it.contains("Dog") && it.contains("CAT") })
    }

    @Test
    fun `CandidateRenamed case-only on self is allowed and updates canonical`() {
        val result = NormalizeCaseTransform.transform(
            narratives(
                DomainEvent.UserRegisteredViaDiscord("alice", "did-alice", "alice", Role.OWNER),
                DomainEvent.ElectionCreated("alice", "Best Sport"),
                DomainEvent.CandidatesAdded("Best Sport", listOf("Tennis")),
                DomainEvent.CandidateRenamed("Best Sport", "Tennis", "TENNIS"),
                DomainEvent.BallotCast(
                    voterName = "alice",
                    electionName = "Best Sport",
                    rankings = listOf(Ranking("tennis", 1)),
                    confirmation = "c1",
                    whenCast = t0,
                ),
            )
        )

        val ok = assertOk(result)
        val cast = ok.events.last().event as DomainEvent.BallotCast
        assertEquals("TENNIS", cast.rankings.single().candidateName)
    }

    @Test
    fun `ElectionDeleted frees the election name slot for re-use`() {
        val result = NormalizeCaseTransform.transform(
            narratives(
                DomainEvent.UserRegisteredViaDiscord("alice", "did-alice", "alice", Role.OWNER),
                DomainEvent.ElectionCreated("alice", "Best Album"),
                DomainEvent.ElectionDeleted("BEST ALBUM"),
                DomainEvent.ElectionCreated("alice", "best album"),
            )
        )

        val ok = assertOk(result)
        // After delete + recreate, the second creation establishes its own
        // canonical case — not a collision.
        val recreated = ok.events.last().event as DomainEvent.ElectionCreated
        assertEquals("best album", recreated.electionName)
    }

    @Test
    fun `UserRemoved frees the user name slot for re-use`() {
        val result = NormalizeCaseTransform.transform(
            narratives(
                DomainEvent.UserRegisteredViaDiscord("Alice", "did-1", "Alice", Role.OWNER),
                DomainEvent.UserRemoved("alice"),
                DomainEvent.UserRegisteredViaDiscord("alice", "did-2", "alice", Role.VOTER),
            )
        )

        val ok = assertOk(result)
        val deleteEvent = ok.events[1].event as DomainEvent.UserRemoved
        assertEquals("Alice", deleteEvent.userName)
        val recreated = ok.events.last().event as DomainEvent.UserRegisteredViaDiscord
        assertEquals("alice", recreated.name)
    }

    @Test
    fun `clean input is a no-op`() {
        val input = narratives(
            DomainEvent.UserRegisteredViaDiscord("alice", "did-alice", "alice", Role.OWNER),
            DomainEvent.ElectionCreated("alice", "Best Color"),
            DomainEvent.CandidatesAdded("Best Color", listOf("Red", "Blue")),
        )

        val ok = assertOk(NormalizeCaseTransform.transform(input))
        assertEquals(0, ok.rewrites)
        assertEquals(input, ok.events)
    }
}
