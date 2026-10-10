package com.seanshubin.vote.integration

import com.seanshubin.vote.domain.ReviewLink
import com.seanshubin.vote.integration.dsl.TestContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Resolving a shared review address back to the review it names.
 *
 * Unauthenticated on purpose: no token is passed anywhere in this file, and
 * that is the feature. The address is derived from names and nothing is
 * stored, so these tests also pin what happens when the names move.
 */
class SharedReviewTest {

    @Test
    fun `a link built from a review resolves back to it`() {
        val context = TestContext()
        val (alice, _) = context.registerUsers("alice")
        val election = alice.createElection("Favorite Rippaverse Book")
        election.addCandidates("Zalen: What Kills Must Die", "Isom 1")
        alice.setCandidateNote(election.name, "Zalen: What Kills Must Die", "clear moral code")

        val found = context.resolve(election.name, "Zalen: What Kills Must Die", "alice")

        assertNotNull(found, "the link should resolve")
        assertEquals("clear moral code", found.text)
        assertEquals("Zalen: What Kills Must Die", found.candidateName)
        assertEquals("alice", found.voterName)
    }

    @Test
    fun `resolution needs no login`() {
        // Stated as an assertion of its own because it is the point of the
        // feature: findSharedReview takes no access token at all, so there is
        // no permission to satisfy and no session to establish.
        val context = TestContext()
        val (alice, _) = context.registerUsers("alice")
        val election = alice.createElection("Drinks")
        election.addCandidates("Tea")
        alice.setCandidateNote(election.name, "Tea", "hot and strong")

        val found = context.service.findSharedReview(
            electionSlug = "drinks",
            candidateSlug = "tea",
            reviewerSlug = "alice",
            checksum = ReviewLink.checksum("Drinks", "Tea", "alice"),
        )
        assertEquals("hot and strong", found?.text)
    }

    @Test
    fun `a wrong checksum resolves to nothing even when the slugs are right`() {
        // Without this the slugs alone would be enough, and the address would
        // no longer identify one triple.
        val context = TestContext()
        val (alice, _) = context.registerUsers("alice")
        val election = alice.createElection("Drinks")
        election.addCandidates("Tea")
        alice.setCandidateNote(election.name, "Tea", "hot and strong")

        assertNull(
            context.service.findSharedReview("drinks", "tea", "alice", "000000"),
            "a bad checksum must not resolve",
        )
    }

    @Test
    fun `candidates that slug alike each resolve to their own review`() {
        // The reason the checksum exists. "Isom 1" and "Isom: 1" are
        // different names that produce the same path segment, so only the
        // checksum can tell these two addresses apart.
        val context = TestContext()
        val (alice, _) = context.registerUsers("alice")
        val election = alice.createElection("Collisions")
        election.addCandidates("Isom 1", "Isom: 1")
        alice.setCandidateNote(election.name, "Isom 1", "the plain one")
        alice.setCandidateNote(election.name, "Isom: 1", "the colonised one")

        assertEquals("isom-1", com.seanshubin.vote.domain.Slug.of("Isom 1"))
        assertEquals("isom-1", com.seanshubin.vote.domain.Slug.of("Isom: 1"))

        assertEquals("the plain one", context.resolve(election.name, "Isom 1", "alice")?.text)
        assertEquals("the colonised one", context.resolve(election.name, "Isom: 1", "alice")?.text)
    }

    @Test
    fun `reviewers that slug alike each resolve to their own review`() {
        val context = TestContext()
        val (alice, _) = context.registerUsers("alice")
        val election = alice.createElection("Drinks")
        election.addCandidates("Tea")
        // Two users whose names differ only by punctuation.
        val (dotted, _) = context.registerUsers("Bad Mr. Frosty")
        val (plain, _) = context.registerUsers("Bad Mr Frosty")
        dotted.setCandidateNote(election.name, "Tea", "with the dot")
        plain.setCandidateNote(election.name, "Tea", "without the dot")

        assertEquals("with the dot", context.resolve(election.name, "Tea", "Bad Mr. Frosty")?.text)
        assertEquals("without the dot", context.resolve(election.name, "Tea", "Bad Mr Frosty")?.text)
    }

    @Test
    fun `a link survives being written with different casing`() {
        // Names are unique case-insensitively, so a sharer who had different
        // casing on screen must still produce a working link.
        val context = TestContext()
        val (alice, _) = context.registerUsers("alice")
        val election = alice.createElection("Favorite Rippaverse Book")
        election.addCandidates("Isom 1")
        alice.setCandidateNote(election.name, "Isom 1", "good")

        val found = context.service.findSharedReview(
            electionSlug = "favorite-rippaverse-book",
            candidateSlug = "isom-1",
            reviewerSlug = "alice",
            checksum = ReviewLink.checksum("FAVORITE RIPPAVERSE BOOK", "isom 1", "Alice"),
        )
        assertEquals("good", found?.text)
    }

    @Test
    fun `a deleted review stops resolving`() {
        // Confirmed as acceptable: it is no mystery why the review is gone.
        val context = TestContext()
        val (alice, _) = context.registerUsers("alice")
        val election = alice.createElection("Drinks")
        election.addCandidates("Tea")
        alice.setCandidateNote(election.name, "Tea", "hot and strong")
        assertNotNull(context.resolve(election.name, "Tea", "alice"))

        alice.setCandidateNote(election.name, "Tea", "")

        assertNull(context.resolve(election.name, "Tea", "alice"), "a deleted review must not resolve")
    }

    @Test
    fun `renaming the election moves the review to a new address`() {
        // The accepted consequence of deriving the address from names. The
        // rename cascades to the note, so the old address names something
        // that no longer exists and the new one works.
        val context = TestContext()
        val (alice, _) = context.registerUsers("alice")
        val election = alice.createElection("Drinks")
        election.addCandidates("Tea")
        alice.setCandidateNote(election.name, "Tea", "hot and strong")

        val renamed = election.rename("Beverages")

        assertNull(
            context.resolve("Drinks", "Tea", "alice"),
            "the old address must stop resolving",
        )
        assertEquals(
            "hot and strong",
            context.resolve(renamed.name, "Tea", "alice")?.text,
            "the review should be reachable at its new address",
        )
    }

    @Test
    fun `unknown names resolve to nothing rather than throwing`() {
        // A dead link is an ordinary outcome and has to produce a page, not
        // an exception.
        val context = TestContext()
        val (alice, _) = context.registerUsers("alice")
        val election = alice.createElection("Drinks")
        election.addCandidates("Tea")
        alice.setCandidateNote(election.name, "Tea", "hot and strong")

        assertNull(context.service.findSharedReview("no-such-election", "tea", "alice", "abc123"))
        assertNull(context.service.findSharedReview("drinks", "no-such-candidate", "alice", "abc123"))
        assertNull(context.service.findSharedReview("drinks", "tea", "no-such-reviewer", "abc123"))
    }

    @Test
    fun `a review by one voter is not reachable at another voter's address`() {
        val context = TestContext()
        val (alice, _) = context.registerUsers("alice")
        val election = alice.createElection("Drinks")
        election.addCandidates("Tea")
        val (bob, _) = context.registerUsers("bob")
        alice.setCandidateNote(election.name, "Tea", "alice's take")

        assertNull(context.resolve(election.name, "Tea", "bob"), "bob has not reviewed Tea")
        assertEquals("alice's take", context.resolve(election.name, "Tea", "alice")?.text)
    }

    /** Builds the address the way a sharer would, then resolves it. */
    private fun TestContext.resolve(
        electionName: String,
        candidateName: String,
        reviewerName: String,
    ) = service.findSharedReview(
        electionSlug = com.seanshubin.vote.domain.Slug.of(electionName),
        candidateSlug = com.seanshubin.vote.domain.Slug.of(candidateName),
        reviewerSlug = com.seanshubin.vote.domain.Slug.of(reviewerName),
        checksum = ReviewLink.checksum(electionName, candidateName, reviewerName),
    )
}
