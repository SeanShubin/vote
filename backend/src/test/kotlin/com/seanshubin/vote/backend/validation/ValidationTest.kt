package com.seanshubin.vote.backend.validation

import com.seanshubin.vote.domain.Ranking
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

    // validateRankingsMatchCandidates tests
    @Test
    fun `validateRankingsMatchCandidates accepts valid rankings`() {
        val rankings = listOf(Ranking("Alice", 1), Ranking("Bob", 2))
        val candidates = listOf("Alice", "Bob", "Charlie")
        // Should not throw
        Validation.validateRankingsMatchCandidates(rankings, candidates)
    }

    @Test
    fun `validateRankingsMatchCandidates rejects unknown candidate`() {
        val rankings = listOf(Ranking("Alice", 1), Ranking("Dave", 2))
        val candidates = listOf("Alice", "Bob", "Charlie")
        val exception = assertFailsWith<IllegalArgumentException> {
            Validation.validateRankingsMatchCandidates(rankings, candidates)
        }
        assertTrue(exception.message!!.contains("unknown"))
        assertTrue(exception.message!!.contains("Dave"))
    }

    @Test
    fun `validateRankingsMatchCandidates rejects multiple unknown candidates`() {
        val rankings = listOf(Ranking("Dave", 1), Ranking("Eve", 2))
        val candidates = listOf("Alice", "Bob")
        val exception = assertFailsWith<IllegalArgumentException> {
            Validation.validateRankingsMatchCandidates(rankings, candidates)
        }
        assertTrue(exception.message!!.contains("Dave"))
        assertTrue(exception.message!!.contains("Eve"))
    }

    // validateCandidateNames tests
    @Test
    fun `validateCandidateNames rejects empty list`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            Validation.validateCandidateNames(emptyList())
        }
        assertTrue(exception.message!!.contains("empty"))
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
}
