package com.seanshubin.vote.domain

import kotlinx.datetime.Instant

/**
 * Pasted-ballot text format for the standalone tally page.
 *
 * Two sections separated by `---`. The index section maps abbreviations to
 * full candidate names; the ballot section lists ranked-choice ballots in
 * compact form. Case-insensitive throughout (matches the rest of the app);
 * original casing is preserved for display.
 *
 *     # comments start with hash
 *     A = Alice Johnson
 *     B = Bob Smith
 *     C = Carol Davis
 *     ---
 *     5: A > B > C       # 5 voters cast A > B > C
 *     3: B = C > A       # = ties candidates at the same rank
 *     2: C > A           # truncated ballots are allowed (B unranked)
 *     A > B              # leading "1:" is optional
 */
object PasteTallyFormat {
    private const val SEPARATOR_CHAR = '-'
    private const val MIN_SEPARATOR_LEN = 3
    private const val COMMENT_CHAR = '#'

    fun parse(text: String): ParseResult {
        val cleaned = text.split('\n').mapIndexed { index, raw ->
            Line(index + 1, stripCommentAndTrim(raw))
        }
        val separatorIndices = cleaned.withIndex()
            .filter { (_, line) -> isSeparator(line.content) }
            .map { it.index }

        val errors = mutableListOf<ParseMessage>()

        if (separatorIndices.isEmpty()) {
            errors.add(ParseMessage(null, "Missing '---' separator between candidate index and ballots"))
            return ParseResult.Failure(errors)
        }
        if (separatorIndices.size > 1) {
            separatorIndices.drop(1).forEach { idx ->
                errors.add(ParseMessage(cleaned[idx].number, "Multiple '---' separators are not allowed"))
            }
            return ParseResult.Failure(errors)
        }

        val separatorIndex = separatorIndices.first()
        val indexLines = cleaned.subList(0, separatorIndex).filter { it.content.isNotEmpty() }
        val ballotLines = cleaned.subList(separatorIndex + 1, cleaned.size).filter { it.content.isNotEmpty() }

        val declarations = parseIndex(indexLines, errors)
        val abbrevLookup = declarations.associateBy { it.abbreviation.lowercase() }

        val ballots = ballotLines.mapNotNull { parseBallotLine(it, abbrevLookup, errors) }

        if (errors.isNotEmpty()) return ParseResult.Failure(errors)

        val usedFullNames = ballots
            .flatMap { ballot -> ballot.rankedGroups.flatten() }
            .map { it.lowercase() }
            .toSet()
        val warnings = declarations
            .filter { it.fullName.lowercase() !in usedFullNames }
            .map { decl ->
                ParseMessage(
                    decl.lineNumber,
                    "Candidate '${decl.fullName}' (${decl.abbreviation}) is declared but never appears in any ballot",
                )
            }

        return ParseResult.Success(declarations, ballots, warnings)
    }

    /**
     * Synthesize the engine input from a parsed result. Each `5: A > B > C`
     * group expands into 5 [Ballot.Identified] with unique synthetic voter
     * names and confirmations — the engine wants identified ballots and the
     * paste page has no real voter identities, so synthesis is the only way
     * to feed the existing tally code without rewriting it.
     */
    fun toBallots(
        parsed: ParseResult.Success,
        electionName: String,
        // The synthetic timestamp has no semantic meaning — the engine just
        // stores it on each ballot. Defaulted so the frontend can call this
        // without depending on kotlinx-datetime.
        whenCast: Instant = Instant.fromEpochMilliseconds(0),
    ): List<Ballot.Identified> {
        val result = mutableListOf<Ballot.Identified>()
        var voterIndex = 0
        for (ballot in parsed.ballots) {
            val rankings = ballot.rankedGroups.flatMapIndexed { rankZeroBased, group ->
                group.map { fullName ->
                    Ranking(candidateName = fullName, rank = rankZeroBased + 1)
                }
            }
            repeat(ballot.count) {
                voterIndex++
                val padded = voterIndex.toString().padStart(VOTER_PAD_WIDTH, '0')
                result.add(
                    Ballot.Identified(
                        voterName = "voter-$padded",
                        electionName = electionName,
                        confirmation = "synthetic-$padded",
                        whenCast = whenCast,
                        rankings = rankings,
                    )
                )
            }
        }
        return result
    }

    private const val VOTER_PAD_WIDTH = 4

    private fun parseIndex(indexLines: List<Line>, errors: MutableList<ParseMessage>): List<CandidateDeclaration> {
        val declarations = mutableListOf<CandidateDeclaration>()
        val byAbbrev = mutableMapOf<String, CandidateDeclaration>()
        val byFullName = mutableMapOf<String, CandidateDeclaration>()
        for (line in indexLines) {
            val eqIdx = line.content.indexOf('=')
            if (eqIdx < 0) {
                errors.add(ParseMessage(line.number, "Expected '<abbreviation> = <full name>'"))
                continue
            }
            val abbrev = line.content.substring(0, eqIdx).trim()
            val fullName = line.content.substring(eqIdx + 1).trim()
            if (abbrev.isEmpty()) {
                errors.add(ParseMessage(line.number, "Abbreviation is empty"))
                continue
            }
            if (fullName.isEmpty()) {
                errors.add(ParseMessage(line.number, "Full name is empty"))
                continue
            }
            if (abbrev.any { it in RESERVED_CHARS }) {
                errors.add(ParseMessage(line.number, "Abbreviation '$abbrev' contains a reserved character (one of $RESERVED_CHARS)"))
                continue
            }
            val abbrevKey = abbrev.lowercase()
            val fullNameKey = fullName.lowercase()
            val priorAbbrev = byAbbrev[abbrevKey]
            if (priorAbbrev != null) {
                errors.add(ParseMessage(line.number, "Duplicate abbreviation '$abbrev' (already declared on line ${priorAbbrev.lineNumber})"))
                continue
            }
            val priorFullName = byFullName[fullNameKey]
            if (priorFullName != null) {
                errors.add(ParseMessage(line.number, "Duplicate candidate '$fullName' (already declared on line ${priorFullName.lineNumber})"))
                continue
            }
            val decl = CandidateDeclaration(abbrev, fullName, line.number)
            declarations.add(decl)
            byAbbrev[abbrevKey] = decl
            byFullName[fullNameKey] = decl
        }
        return declarations
    }

    private fun parseBallotLine(
        line: Line,
        abbrevLookup: Map<String, CandidateDeclaration>,
        errors: MutableList<ParseMessage>,
    ): ParsedBallot? {
        val colonIdx = line.content.indexOf(':')
        val count: Int
        val rankingText: String
        if (colonIdx >= 0) {
            val countText = line.content.substring(0, colonIdx).trim()
            val parsedCount = countText.toIntOrNull()
            if (parsedCount == null) {
                errors.add(ParseMessage(line.number, "Invalid ballot count '$countText' (expected a positive integer)"))
                return null
            }
            if (parsedCount <= 0) {
                errors.add(ParseMessage(line.number, "Ballot count must be positive, got $parsedCount"))
                return null
            }
            count = parsedCount
            rankingText = line.content.substring(colonIdx + 1).trim()
        } else {
            count = 1
            rankingText = line.content
        }

        if (rankingText.isEmpty()) {
            errors.add(ParseMessage(line.number, "Ballot has no candidates"))
            return null
        }

        val rankedGroups = mutableListOf<List<String>>()
        val seenInBallot = mutableSetOf<String>()
        var ballotHasError = false
        for (groupText in rankingText.split('>').map { it.trim() }) {
            if (groupText.isEmpty()) {
                errors.add(ParseMessage(line.number, "Empty rank group in ballot (stray '>' or '=')"))
                ballotHasError = true
                continue
            }
            val resolved = mutableListOf<String>()
            for (abbrevRaw in groupText.split('=').map { it.trim() }) {
                if (abbrevRaw.isEmpty()) {
                    errors.add(ParseMessage(line.number, "Empty abbreviation in ballot"))
                    ballotHasError = true
                    continue
                }
                val decl = abbrevLookup[abbrevRaw.lowercase()]
                if (decl == null) {
                    errors.add(ParseMessage(line.number, "Unknown abbreviation '$abbrevRaw'"))
                    ballotHasError = true
                    continue
                }
                if (!seenInBallot.add(abbrevRaw.lowercase())) {
                    errors.add(ParseMessage(line.number, "Candidate '$abbrevRaw' appears more than once in the same ballot"))
                    ballotHasError = true
                    continue
                }
                resolved.add(decl.fullName)
            }
            if (resolved.isNotEmpty()) rankedGroups.add(resolved)
        }
        if (ballotHasError) return null
        return ParsedBallot(count, rankedGroups, line.number)
    }

    private fun stripCommentAndTrim(raw: String): String {
        val hashIdx = raw.indexOf(COMMENT_CHAR)
        val withoutComment = if (hashIdx >= 0) raw.substring(0, hashIdx) else raw
        return withoutComment.trim()
    }

    private fun isSeparator(content: String): Boolean =
        content.length >= MIN_SEPARATOR_LEN && content.all { it == SEPARATOR_CHAR }

    private const val RESERVED_CHARS = "><=,;:#"

    private data class Line(val number: Int, val content: String)
}

data class CandidateDeclaration(
    val abbreviation: String,
    val fullName: String,
    val lineNumber: Int,
)

data class ParsedBallot(
    val count: Int,
    /**
     * Outer list is rank tier (index 0 = rank 1, etc). Inner list is the
     * full candidate names tied at that rank. A truncated ballot simply has
     * fewer entries — candidates that don't appear are unranked.
     */
    val rankedGroups: List<List<String>>,
    val lineNumber: Int,
)

data class ParseMessage(val line: Int?, val message: String)

sealed interface ParseResult {
    data class Success(
        val candidates: List<CandidateDeclaration>,
        val ballots: List<ParsedBallot>,
        val warnings: List<ParseMessage>,
    ) : ParseResult

    data class Failure(val errors: List<ParseMessage>) : ParseResult
}
