package com.seanshubin.vote.tools.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.seanshubin.vote.tools.lib.Output
import com.seanshubin.vote.tools.lib.ProjectPaths
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

class ConvertScenarios : CliktCommand(name = "convert-scenarios") {
    override fun help(context: Context) =
        "Convert condorcet3 input.txt files into the vote scenario JSON format."

    val source: String by option(
        "--source",
        help = "Path to the condorcet3 test-data directory (containing one folder per scenario)."
    ).default("../condorcet3/jvm-backend/src/test/resources/test-data")

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    override fun run() {
        val sourceDir = Path.of(source).toAbsolutePath().normalize()
        val targetDir = ProjectPaths.scenarioDataDir

        if (!sourceDir.exists() || !sourceDir.isDirectory()) {
            Output.error("condorcet3 data directory not found: $sourceDir")
        }

        Output.banner("Converting scenarios to JSON format")
        println("Source: $sourceDir")
        println("Target: $targetDir")
        println()

        Files.createDirectories(targetDir)

        val scenarioDirs = sourceDir.listDirectoryEntries()
            .filter { it.isDirectory() }
            .sortedBy { it.name }

        for (scenarioDir in scenarioDirs) {
            val scenarioName = scenarioDir.name
            val inputFile = scenarioDir.resolve("input.txt")
            if (!inputFile.exists() || !inputFile.isRegularFile()) {
                println("WARNING: No input.txt found in $scenarioName, skipping")
                continue
            }

            print("Converting $scenarioName...")
            val scenario = convert(scenarioName, inputFile)
            val outputFile = targetDir.resolve("$scenarioName.json")
            outputFile.writeText(json.encodeToString(scenario))

            println(
                " ${scenario.candidates.size} candidates, " +
                    "${scenario.voters.size} voters, " +
                    "${scenario.ballots.size} ballots"
            )
        }

        println()
        Output.success("Conversion complete!")
        println("Generated JSON files in: $targetDir")
    }

    private fun convert(scenarioName: String, inputFile: Path): Scenario {
        val text = inputFile.readText()
        val sections = parseSections(text)

        val candidates = sections.candidates.map { name ->
            ScenarioCandidate(originalName = name, displayName = hyphenatedToProperCase(name))
        }
        val voters = sections.voters.map { name ->
            ScenarioVoter(
                originalName = name,
                displayName = hyphenatedToProperCase(name),
                email = hyphenatedToEmail(name)
            )
        }
        val ballots = sections.ballotLines.map { line ->
            val tokens = line.trim().split(Regex("\\s+"))
            require(tokens.size >= 2) { "Malformed ballot line in $scenarioName: $line" }
            val voterOriginal = tokens[0]
            val confirmation = tokens[1]
            val rankPairs = tokens.drop(2).chunked(2).filter { it.size == 2 }
            val rankings = rankPairs.map { (rank, candidate) ->
                ScenarioRanking(
                    candidateOriginalName = candidate,
                    candidateDisplayName = hyphenatedToProperCase(candidate),
                    rank = rank.toInt()
                )
            }
            ScenarioBallot(
                voterOriginalName = voterOriginal,
                voterDisplayName = hyphenatedToProperCase(voterOriginal),
                confirmation = confirmation,
                rankings = rankings
            )
        }

        val scenarioNumber = Regex("^[0-9]+").find(scenarioName)?.value ?: ""
        val displayName = scenarioDisplayName(scenarioName)

        return Scenario(
            scenarioNumber = scenarioNumber,
            scenarioName = scenarioName,
            displayName = displayName,
            electionName = "$displayName Election",
            ownerName = "owner",
            candidates = candidates,
            voters = voters,
            ballots = ballots
        )
    }

    private data class Sections(
        val candidates: List<String>,
        val voters: List<String>,
        val ballotLines: List<String>
    )

    private fun parseSections(text: String): Sections {
        val candidates = mutableListOf<String>()
        val voters = mutableListOf<String>()
        val ballots = mutableListOf<String>()
        var section: String? = null

        text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            when {
                trimmed.startsWith("candidates (") -> { section = "candidates"; return@forEach }
                trimmed.startsWith("eligible-to-vote (") -> { section = "voters"; return@forEach }
                trimmed.startsWith("ballots (") -> { section = "ballots"; return@forEach }
            }
            when (section) {
                "candidates" -> candidates.add(trimmed.split(Regex("\\s+")).first())
                "voters" -> voters.add(trimmed.split(Regex("\\s+")).first())
                "ballots" -> ballots.add(trimmed)
            }
        }
        return Sections(candidates, voters, ballots)
    }

    private fun hyphenatedToProperCase(input: String): String {
        if (input == "<no-candidate>") return input
        return input.split("-").joinToString(" ") { word ->
            if (word.isEmpty()) word
            else word[0].uppercaseChar() + word.drop(1).lowercase()
        }
    }

    private fun hyphenatedToEmail(input: String): String {
        val cleaned = input.trim('<', '>').lowercase().replace('-', '.')
        return "$cleaned@example.com"
    }

    private fun scenarioDisplayName(scenarioName: String): String {
        val withoutNumber = scenarioName.replace(Regex("^[0-9]+-"), "")
        return withoutNumber.split('-').joinToString(" ") { word ->
            if (word.isEmpty()) word else word[0].uppercaseChar() + word.drop(1)
        }
    }
}
