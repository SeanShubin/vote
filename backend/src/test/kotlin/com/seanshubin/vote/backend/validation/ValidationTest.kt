package com.seanshubin.vote.backend.validation

import com.seanshubin.vote.domain.Ranking
import com.seanshubin.vote.domain.RankingKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ValidationTest {

    // validateRankings tests
    @Test
    fun `validateRankings rejects empty list`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            Validation.validateRankings(emptyList())
        }
        assertTrue(exception.message!!.contains("empty"))
    }

    @Test
    fun `validateRankings validates each candidate name`() {
        val rankings = listOf(
            Ranking("  Alice  ", 1),
            Ranking("Bob", 2)
        )
        val valid = Validation.validateRankings(rankings)
        assertEquals(2, valid.size)
        // Candidate names should be validated (trimmed/normalized)
        assertEquals("Alice", valid[0].candidateName)
        assertEquals("Bob", valid[1].candidateName)
    }

    @Test
    fun `validateRankings rejects negative ranks`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            Validation.validateRankings(listOf(Ranking("Alice", -1)))
        }
        assertTrue(exception.message!!.contains("positive"))
    }

    @Test
    fun `validateRankings rejects zero rank`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            Validation.validateRankings(listOf(Ranking("Alice", 0)))
        }
        assertTrue(exception.message!!.contains("positive"))
    }

    @Test
    fun `validateRankings allows null ranks`() {
        val rankings = listOf(Ranking("Alice", null), Ranking("Bob", 1))
        val valid = Validation.validateRankings(rankings)
        assertEquals(2, valid.size)
    }

    @Test
    fun `validateRankings preserves the tier annotation`() {
        // Tier is part of what the voter chose; if validation reconstructs
        // a Ranking without copying tier through, the projection at tally
        // time wouldn't know which tier each candidate cleared.
        val rankings = listOf(
            Ranking("Alice", 1, RankingKind.CANDIDATE, tier = "Gold"),
            Ranking("Bob", 2, RankingKind.CANDIDATE, tier = "Silver"),
            Ranking("Carol", 3, RankingKind.CANDIDATE, tier = null),
        )
        val valid = Validation.validateRankings(rankings)
        assertEquals(rankings.map { it.tier }, valid.map { it.tier })
    }

    @Test
    fun `validateRankings rejects tier-kind rankings`() {
        // Storage rule after the tier-as-annotation refactor: voters cast
        // candidate rankings only. Tier markers are produced by the
        // projection at compute time, never stored.
        val ex = assertFailsWith<IllegalArgumentException> {
            Validation.validateRankings(
                listOf(
                    Ranking("Alice", 1, RankingKind.CANDIDATE),
                    Ranking("Gold", 2, RankingKind.TIER),
                )
            )
        }
        assertTrue(ex.message!!.contains("CANDIDATE"))
    }

    @Test
    fun `validateRankings rejects tier annotation when rank is null`() {
        // rank=null means the voter abstained — no tier judgment either.
        val ex = assertFailsWith<IllegalArgumentException> {
            Validation.validateRankings(
                listOf(Ranking("Alice", null, RankingKind.CANDIDATE, tier = "Gold"))
            )
        }
        assertTrue(ex.message!!.contains("tier"))
    }

    // validateRankingsMatchCandidates tests
    @Test
    fun `validateRankingsMatchCandidates accepts valid rankings`() {
        val rankings = listOf(Ranking("Alice", 1), Ranking("Bob", 2))
        val candidates = listOf("Alice", "Bob", "Charlie")
        // Should not throw
        Validation.validateRankingsMatchCandidates(rankings, candidates, tiers = emptyList())
    }

    @Test
    fun `validateRankingsMatchCandidates rejects unknown candidate`() {
        val rankings = listOf(Ranking("Alice", 1), Ranking("Dave", 2))
        val candidates = listOf("Alice", "Bob", "Charlie")
        val exception = assertFailsWith<IllegalArgumentException> {
            Validation.validateRankingsMatchCandidates(rankings, candidates, tiers = emptyList())
        }
        assertTrue(exception.message!!.contains("unknown"))
        assertTrue(exception.message!!.contains("Dave"))
    }

    @Test
    fun `validateRankingsMatchCandidates rejects multiple unknown candidates`() {
        val rankings = listOf(Ranking("Dave", 1), Ranking("Eve", 2))
        val candidates = listOf("Alice", "Bob")
        val exception = assertFailsWith<IllegalArgumentException> {
            Validation.validateRankingsMatchCandidates(rankings, candidates, tiers = emptyList())
        }
        assertTrue(exception.message!!.contains("Dave"))
        assertTrue(exception.message!!.contains("Eve"))
    }

    @Test
    fun `validateRankingsMatchCandidates rejects unknown tier annotations`() {
        // A ballot whose tier annotation doesn't match any configured
        // tier was built against a stale view of the election; reject so
        // the stray label can't sneak into the projected ballot.
        val rankings = listOf(Ranking("Alice", 1, RankingKind.CANDIDATE, tier = "Platinum"))
        val ex = assertFailsWith<IllegalArgumentException> {
            Validation.validateRankingsMatchCandidates(
                rankings,
                candidates = listOf("Alice"),
                tiers = listOf("Gold", "Silver"),
            )
        }
        assertTrue(ex.message!!.contains("Platinum"))
    }

    // validateCandidateNames tests
    @Test
    fun `validateCandidateNames accepts empty list (lets owner clear all candidates)`() {
        val valid = Validation.validateCandidateNames(emptyList())
        assertEquals(emptyList<String>(), valid)
    }

    @Test
    fun `validateCandidateNames validates and normalizes each name`() {
        val names = listOf("  Alice  ", "Bob", "  Charlie  ")
        val valid = Validation.validateCandidateNames(names)
        assertEquals(listOf("Alice", "Bob", "Charlie"), valid)
    }

    @Test
    fun `validateCandidateNames detects duplicates`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            Validation.validateCandidateNames(listOf("Alice", "Bob", "Alice"))
        }
        assertTrue(exception.message!!.contains("Duplicate"))
        assertTrue(exception.message!!.contains("Alice"))
    }

    @Test
    fun `validateCandidateNames detects duplicates after normalization`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            Validation.validateCandidateNames(listOf("Alice", "  Alice  "))
        }
        assertTrue(exception.message!!.contains("Duplicate"))
    }

    @Test
    fun `validateCandidateNames rejects empty name`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            Validation.validateCandidateNames(listOf("Alice", "", "Bob"))
        }
        assertTrue(exception.message!!.contains("empty"))
    }

    @Test
    fun `validateCandidateNames rejects whitespace-only name`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            Validation.validateCandidateNames(listOf("Alice", "   ", "Bob"))
        }
        assertTrue(exception.message!!.contains("empty"))
    }

    @Test
    fun `validateCandidateNames rejects too-long name`() {
        val longName = "a".repeat(201)
        val exception = assertFailsWith<IllegalArgumentException> {
            Validation.validateCandidateNames(listOf(longName))
        }
        assertTrue(exception.message!!.contains("200"))
    }

    // validateVoterNames tests
    @Test
    fun `validateVoterNames rejects empty list`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            Validation.validateVoterNames(emptyList())
        }
        assertTrue(exception.message!!.contains("empty"))
    }

    @Test
    fun `validateVoterNames validates and normalizes each name`() {
        val names = listOf("  alice  ", "bob", "  charlie  ")
        val valid = Validation.validateVoterNames(names)
        assertEquals(listOf("alice", "bob", "charlie"), valid)
    }

    @Test
    fun `validateVoterNames detects duplicates`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            Validation.validateVoterNames(listOf("alice", "bob", "alice"))
        }
        assertTrue(exception.message!!.contains("Duplicate"))
        assertTrue(exception.message!!.contains("alice"))
    }

    @Test
    fun `validateVoterNames detects duplicates after normalization`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            Validation.validateVoterNames(listOf("alice", "  alice  "))
        }
        assertTrue(exception.message!!.contains("Duplicate"))
    }

    @Test
    fun `validateVoterNames rejects empty name`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            Validation.validateVoterNames(listOf("alice", "", "bob"))
        }
        assertTrue(exception.message!!.contains("empty"))
    }

    @Test
    fun `validateVoterNames rejects whitespace-only name`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            Validation.validateVoterNames(listOf("alice", "   ", "bob"))
        }
        assertTrue(exception.message!!.contains("empty"))
    }

    @Test
    fun `validateVoterNames rejects too-long name`() {
        val longName = "a".repeat(201)
        val exception = assertFailsWith<IllegalArgumentException> {
            Validation.validateVoterNames(listOf(longName))
        }
        assertTrue(exception.message!!.contains("200"))
    }

    // Existing validation functions tests (spot checks)
    @Test
    fun `validateUserName normalizes whitespace`() {
        val valid = Validation.validateUserName("  john   doe  ")
        assertEquals("john doe", valid)
    }

    @Test
    fun `validateElectionName normalizes whitespace`() {
        val valid = Validation.validateElectionName("  Best   Language  ")
        assertEquals("Best Language", valid)
    }

    @Test
    fun `validateCandidateName normalizes whitespace`() {
        val valid = Validation.validateCandidateName("  Python   3  ")
        assertEquals("Python 3", valid)
    }

    @Test
    fun `validateCandidatesAndTiersDistinct passes when sets are disjoint`() {
        Validation.validateCandidatesAndTiersDistinct(
            candidates = listOf("Alice", "Bob"),
            tiers = listOf("Gold", "Silver"),
        )
    }

    @Test
    fun `validateCandidatesAndTiersDistinct passes when both lists are empty`() {
        Validation.validateCandidatesAndTiersDistinct(emptyList(), emptyList())
    }

    @Test
    fun `validateCandidatesAndTiersDistinct rejects exact name collision`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            Validation.validateCandidatesAndTiersDistinct(
                candidates = listOf("Alice", "Pass"),
                tiers = listOf("Gold", "Pass"),
            )
        }
        assertTrue(ex.message!!.contains("Pass"), "expected message to mention 'Pass', got: ${ex.message}")
    }

    @Test
    fun `validateCandidatesAndTiersDistinct rejects case-only collision`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            Validation.validateCandidatesAndTiersDistinct(
                candidates = listOf("Pass"),
                tiers = listOf("pass"),
            )
        }
        assertTrue(ex.message!!.contains("Pass") && ex.message!!.contains("pass"))
    }

    @Test
    fun `validateCandidatesAndTiersDistinct reports every collision`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            Validation.validateCandidatesAndTiersDistinct(
                candidates = listOf("Alice", "Bob", "Carol"),
                tiers = listOf("Alice", "Carol"),
            )
        }
        assertTrue(ex.message!!.contains("Alice"))
        assertTrue(ex.message!!.contains("Carol"))
    }
}
