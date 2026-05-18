package com.seanshubin.vote.frontend

import androidx.compose.runtime.*
import com.seanshubin.vote.domain.CandidateDeclaration
import com.seanshubin.vote.domain.ElectionTally
import com.seanshubin.vote.domain.ParseMessage
import com.seanshubin.vote.domain.ParseResult
import com.seanshubin.vote.domain.PasteTallyFormat
import com.seanshubin.vote.domain.RankingSide
import com.seanshubin.vote.domain.Tally
import org.jetbrains.compose.web.attributes.*
import org.jetbrains.compose.web.dom.*

private const val PLACEHOLDER = """# Example
# Map abbreviations to candidate names above the --- separator,
# then list ballots below. Each ballot line is "<count>: <ranking>".
# Use > for strict preference and = for ties.

A = Alice Johnson
B = Bob Smith
C = Carol Davis
---
5: A > B > C
3: B = C > A
2: C > A
"""

private data class Example(val label: String, val description: String, val text: String)

private val EXAMPLES = listOf(
    Example(
        label = "Pairwise vs first-past-the-post",
        description = "100 voters split 30/30/40. First-past-the-post crowns radical-changes; pairwise picks minor-improvements, which 70% of voters preferred over the FPTP winner.",
        text = """
            # From the methodology docs: 100 voters split 30/30/40.
            # First-past-the-post would pick radical-changes, but pairwise
            # ranking surfaces minor-improvements as the actual consensus.
            M = minor-improvements
            S = status-quo
            R = radical-changes
            ---
            30: M > S > R
            30: S > M > R
            40: R > M > S
        """.trimIndent() + "\n",
    ),
    Example(
        label = "Reducing tactical voting",
        description = "Under first-past-the-post, the 2 radical-changes voters would have to abandon their top choice to avoid throwing the election. Pairwise lets them rank honestly and the consensus winner still emerges.",
        text = """
            # How pairwise methods reduce tactical voting.
            # Under FPTP the 2 radical-changes voters would have to vote
            # status-quo to avoid throwing the election; under pairwise
            # they can rank honestly and minor-improvements still wins.
            M = minor-improvements
            S = status-quo
            R = radical-changes
            ---
            3: M > S > R
            4: S > M > R
            2: R > M > S
        """.trimIndent() + "\n",
    ),
    Example(
        label = "Pairwise vs instant-runoff",
        description = "Every voter has a different top choice but agrees on a strong second. Instant-runoff eliminates the consensus candidate first; pairwise picks them.",
        text = """
            # Why pairwise methods are superior to instant-runoff voting.
            # Every voter ranks "satisfactory" second, yet IRV eliminates
            # it first because nobody picked it as their top choice.
            # Pairwise ranks: satisfactory > niche > bought > cult.
            N = niche
            S = satisfactory
            B = bought
            C = cult
            ---
            4: N > S > B > C
            3: B > S > N > C
            3: C > S > N > B
        """.trimIndent() + "\n",
    ),
    Example(
        label = "Cycle resolved by Ranked Pairs",
        description = "Rock-paper-scissors with a 10th tiebreaking ballot. Ranked Pairs locks the strongest contests and skips the one that would close a cycle.",
        text = """
            # 9 voters form a perfect 3-way tie; a 10th ballot breaks it.
            # Pairwise produces a cycle (rock > scissors > paper > rock),
            # so Ranked Pairs locks the two strongest contests and skips
            # the weakest — final order: rock > scissors > paper.
            R = rock
            P = paper
            S = scissors
            ---
            3: R > S > P
            3: P > R > S
            3: S > P > R
            1: R > S > P
        """.trimIndent() + "\n",
    ),
    Example(
        label = "Perfectly-tied cycle",
        description = "Three contests at identical strength (4 to 2) form a cycle with no honest tiebreaker — all three candidates tie at the same place.",
        text = """
            # Every pair is a 4-to-2 contest, and they form a cycle:
            # A > B, B > C, C > A. No contest is stronger than another,
            # so the system refuses to pick. A, B, C tie at one place.
            A = A
            B = B
            C = C
            ---
            2: A > B > C
            2: B > C > A
            2: C > A > B
        """.trimIndent() + "\n",
    ),
)

@Composable
fun PasteTallyPage(onBack: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var tally by remember { mutableStateOf<ElectionTally?>(null) }
    var tallyCandidates by remember { mutableStateOf<List<CandidateDeclaration>>(emptyList()) }
    var ballotsExpanded by remember { mutableStateOf(0) }

    val parseResult = remember(text) {
        if (text.isBlank()) null else PasteTallyFormat.parse(text)
    }

    Div({ classes("container") }) {
        H1 { Text("Sandbox & Examples") }
        P {
            Text(
                "Drop ranked-choice ballots in below and see the Ranked Pairs result. " +
                    "No account or election setup required — this is a calculator that " +
                    "runs entirely in your browser."
            )
        }

        FormatHelp()

        ExamplePicker(
            onLoad = { example ->
                text = example.text
                tally = null
            },
        )

        TextArea(text) {
            classes("paste-tally-input")
            attr("rows", "16")
            attr("spellcheck", "false")
            placeholder(PLACEHOLDER)
            onInput {
                text = it.value
                // Drop any prior tally — leaving stale results below a
                // changed input would mislead. Re-click Tally to recompute.
                tally = null
            }
        }

        when (val result = parseResult) {
            null -> P({ classes("paste-tally-status") }) {
                Text("Paste ballots above to begin.")
            }
            is ParseResult.Failure -> MessageList("Errors", result.errors, "error")
            is ParseResult.Success -> {
                if (result.warnings.isNotEmpty()) {
                    MessageList("Warnings", result.warnings, "warning")
                }
                P({ classes("paste-tally-status") }) {
                    Text("${result.candidates.size} candidates, ${result.ballots.sumOf { it.count }} ballots ready.")
                }
                Div({ classes("button-row") }) {
                    Button({
                        onClick {
                            val ballots = PasteTallyFormat.toBallots(
                                parsed = result,
                                electionName = "(pasted)",
                            )
                            val computed = Tally.countBallots(
                                electionName = "(pasted)",
                                side = RankingSide.PUBLIC,
                                candidates = result.candidates.map { it.fullName },
                                tiers = emptyList(),
                                ballots = ballots,
                            )
                            tally = ElectionTally(tally = computed, tiers = emptyList(), sections = emptyList())
                            tallyCandidates = result.candidates
                            ballotsExpanded = ballots.size
                        }
                    }) {
                        Text("Tally")
                    }
                }
            }
        }

        tally?.let { electionTally ->
            ResultsSection(electionTally, tallyCandidates, ballotsExpanded)
        }

        Div({ classes("button-row") }) {
            Button({ onClick { onBack() } }) { Text("Back") }
        }
    }
}

@Composable
private fun ResultsSection(
    electionTally: ElectionTally,
    candidates: List<CandidateDeclaration>,
    ballotCount: Int,
) {
    Div({ classes("paste-tally-results") }) {
        H2 { Text("Results") }

        H3 { Text("Final ranking") }
        // <Ul> with list-style:none — the rank label comes from place.rank,
        // not from browser auto-numbering, so tied candidates correctly
        // share a rank (e.g. two "1st"s) and the next rank is skipped.
        Ul({ classes("paste-tally-places") }) {
            electionTally.tally.places.forEach { place ->
                Li {
                    Span({ classes("paste-tally-place-rank") }) { Text(ordinal(place.rank)) }
                    val abbrev = candidates.firstOrNull { it.fullName == place.candidateName }?.abbreviation
                    if (abbrev != null) {
                        Span({ classes("paste-tally-abbrev") }) { Text(abbrev) }
                    }
                    Text(place.candidateName)
                }
            }
        }

        H3 { Text("Ballot summary") }
        P {
            Text("$ballotCount ballot${if (ballotCount == 1) "" else "s"} tallied across ${candidates.size} candidates.")
        }

        H3 { Text("Ranked Pairs process") }
        renderProcessDetail(electionTally)
    }
}

@Composable
private fun ExamplePicker(onLoad: (Example) -> Unit) {
    Div({ classes("paste-tally-examples") }) {
        H3 { Text("Load an example") }
        P {
            Text(
                "Each example is a small dataset from the methodology docs. " +
                    "Loading one replaces the textarea content."
            )
        }
        Div({ classes("paste-tally-example-buttons") }) {
            EXAMPLES.forEach { example ->
                Button({
                    attr("type", "button")
                    attr("title", example.description)
                    onClick { onLoad(example) }
                }) {
                    Text(example.label)
                }
            }
        }
    }
}

@Composable
private fun FormatHelp() {
    Div({ classes("paste-tally-help") }) {
        H3 { Text("Format") }
        P {
            Text(
                "Two sections separated by --- on its own line. The index maps " +
                    "abbreviations to candidate names; the ballot section lists " +
                    "ballots in compact form. Everything is case-insensitive."
            )
        }
        Ul {
            Li { Text("Index lines: \"AB = Full Candidate Name\"") }
            Li { Text("Separator: three or more dashes on a line by themselves: ---") }
            Li { Text("Ballot lines: \"<count>: A > B > C\"  (count defaults to 1)") }
            Li { Text("Use > for strict preference, = for tied rank") }
            Li { Text("Truncated ballots OK — candidates you don't list are unranked") }
            Li { Text("# starts a comment; blank lines are ignored") }
        }
    }
}

@Composable
private fun MessageList(heading: String, messages: List<ParseMessage>, cssClass: String) {
    Div({ classes("paste-tally-messages") }) {
        H3 { Text(heading) }
        Ul({ classes(cssClass) }) {
            messages.forEach { msg ->
                Li {
                    if (msg.line != null) {
                        Span({ classes("paste-tally-line-no") }) { Text("Line ${msg.line}:") }
                        Text(" ${msg.message}")
                    } else {
                        Text(msg.message)
                    }
                }
            }
        }
    }
}

