package com.seanshubin.vote.tools.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.seanshubin.vote.contract.AccessToken
import com.seanshubin.vote.domain.DomainEvent
import com.seanshubin.vote.domain.Ranking
import com.seanshubin.vote.domain.Role
import com.seanshubin.vote.tools.lib.InProcessService
import com.seanshubin.vote.tools.lib.NarrativeEvent
import com.seanshubin.vote.tools.lib.Output
import com.seanshubin.vote.tools.lib.ProjectPaths
import kotlinx.datetime.Clock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

class GenerateScenarioEventLog : CliktCommand(name = "generate-scenario-event-log") {
    private val outputArg: String? by argument(name = "output", help = "Path to write the JSONL event-log file. Defaults to .local/scenario-snapshots/scenarios.jsonl.").optional()
    private val force by option("--force", help = "Overwrite the output file if it already exists.").flag()
    private val ownerName by option("--owner", help = "Username of the synthetic owner that hosts every election (default: owner).").default("owner")

    override fun help(context: Context) =
        "Build a single JSONL event log that demonstrates every condorcet scenario in scenario-data/. " +
            "Skips 07-ballot-can-have-ties (the app does not support tied ranks within a single ballot). " +
            "Use the output with: scripts/dev launch-from-snapshot --snapshot <output>"

    override fun run() {
        val output = resolveOutput()
        if (output.exists() && !force) {
            Output.error("File already exists: $output (pass --force to overwrite)")
        }

        val scenarioDir = ProjectPaths.scenarioDataDir
        if (!scenarioDir.exists()) {
            Output.error(
                "scenario-data directory not found: $scenarioDir\n" +
                    "Run scripts/dev convert-scenarios --source <path-to-condorcet-test-data> first."
            )
        }

        val scenarios = scenarioDir.listDirectoryEntries()
            .filter { it.name.endsWith(".json") }
            .filter { it.name != "07-ballot-can-have-ties.json" }
            .sortedBy { it.name }
        if (scenarios.isEmpty()) {
            Output.error("No scenario JSONs found in $scenarioDir")
        }

        Output.banner("Generating scenario event log")
        println("Source:    $scenarioDir")
        println("Skipping:  07-ballot-can-have-ties (tied ranks not supported)")
        println("Scenarios: ${scenarios.size}")
        println("Output:    $output")
        println()

        val parser = Json { ignoreUnknownKeys = true }
        val parsedScenarios = scenarios.map { parser.decodeFromString<Scenario>(it.readText()) }

        val host = InProcessService()
        val service = host.service
        // Seed users by appending UserRegisteredViaDiscord events directly to
        // the event log — the password-registration path is gone with the
        // Discord-only login refactor, and the in-process service has no
        // Discord OAuth configured to issue tokens. The synthetic discord_id
        // values just need to be unique per user; they never leave this
        // generator.
        val ownerToken = seedOwner(host)
        val voterTokens = seedAllVoters(host, parsedScenarios)
        service.synchronize()

        parsedScenarios.forEach { scenario ->
            loadScenario(service, scenario, ownerToken, voterTokens)
        }

        val envelopes = host.eventLog.eventsToSync(0L)
        writeJsonl(output, envelopes)

        val bytes = Files.size(output)
        Output.success(
            "Wrote ${envelopes.size} event(s) to $output ($bytes bytes). " +
                "Run: scripts/dev launch-from-snapshot --snapshot $output"
        )
    }

    private fun resolveOutput(): Path {
        val explicit = outputArg
        if (explicit != null) return Path.of(explicit)
        val dir = ProjectPaths.scenarioSnapshotDir
        Files.createDirectories(dir)
        return dir.resolve("scenarios.jsonl")
    }

    private fun seedOwner(host: InProcessService): AccessToken {
        println("Seeding owner ($ownerName)...")
        host.eventLog.appendEvent(
            "system",
            Clock.System.now(),
            DomainEvent.UserRegisteredViaDiscord(
                name = ownerName,
                discordId = SYNTHETIC_DISCORD_PREFIX + ownerName,
                discordDisplayName = ownerName,
                role = Role.PRIMARY_ROLE,
            ),
        )
        return AccessToken(ownerName, Role.PRIMARY_ROLE)
    }

    private fun seedAllVoters(
        host: InProcessService,
        scenarios: List<Scenario>,
    ): Map<String, AccessToken> {
        // De-dupe by voter display name across all scenarios. The same Alice
        // appearing in scenarios 1 and 5 is treated as one user voting in both.
        val uniqueVoters = scenarios
            .flatMap { it.voters }
            .distinctBy { it.displayName }

        println("Seeding ${uniqueVoters.size} unique voter(s) across ${scenarios.size} scenarios...")
        val tokens = mutableMapOf<String, AccessToken>()
        uniqueVoters.forEach { voter ->
            host.eventLog.appendEvent(
                "system",
                Clock.System.now(),
                DomainEvent.UserRegisteredViaDiscord(
                    name = voter.displayName,
                    discordId = SYNTHETIC_DISCORD_PREFIX + voter.displayName,
                    discordDisplayName = voter.displayName,
                    role = Role.VOTER,
                ),
            )
            tokens[voter.displayName] = AccessToken(voter.displayName, Role.VOTER)
        }
        Output.success("Seeded owner + ${tokens.size} voters")
        return tokens
    }

    private fun loadScenario(
        service: com.seanshubin.vote.contract.Service,
        scenario: Scenario,
        ownerToken: AccessToken,
        voterTokens: Map<String, AccessToken>,
    ) {
        // Prefix with the scenario number so the elections sort naturally and
        // are easy to locate in the UI.
        val electionName = "${scenario.scenarioNumber} - ${scenario.displayName}"
        val description = "Condorcet test scenario ${scenario.scenarioNumber}: ${scenario.displayName}"

        println("[${scenario.scenarioNumber}] $electionName — ${scenario.candidates.size} candidates, ${scenario.ballots.size} ballots")
        service.addElection(ownerToken, ownerName, electionName, description)
        service.setCandidates(ownerToken, electionName, scenario.candidates.map { it.displayName })

        scenario.ballots.forEach { ballot ->
            val token = voterTokens[ballot.voterDisplayName]
                ?: error("No registered voter for ballot in scenario ${scenario.scenarioNumber}: ${ballot.voterDisplayName}")
            val rankings = ballot.rankings.map { ranking ->
                Ranking(
                    candidateName = ranking.candidateDisplayName,
                    rank = ranking.rank,
                )
            }
            service.castBallot(token, ballot.voterDisplayName, electionName, rankings)
        }
    }

    private fun writeJsonl(file: Path, envelopes: List<com.seanshubin.vote.domain.EventEnvelope>) {
        val json = Json { encodeDefaults = true }
        Files.newBufferedWriter(
            file,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        ).use { writer ->
            envelopes.forEach { envelope ->
                val narrative = NarrativeEvent(
                    whenHappened = envelope.whenHappened,
                    authority = envelope.authority,
                    event = envelope.event,
                )
                writer.write(json.encodeToString(narrative))
                writer.newLine()
            }
        }
    }

    companion object {
        // Discord IDs are normally snowflakes from Discord. For synthetic
        // scenario data we just need any unique-per-user value; the prefix
        // makes the fakery legible if anyone inspects the projection.
        private const val SYNTHETIC_DISCORD_PREFIX = "synthetic-"
    }
}
