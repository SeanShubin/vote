package com.seanshubin.vote.tools.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.seanshubin.vote.tools.lib.NarrativeEvent
import com.seanshubin.vote.tools.lib.Output
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists

/**
 * One-shot datafix: normalize case across an event-log backup file.
 * See [NormalizeCaseTransform] for the transformation contract.
 *
 * Workflow:
 *   1. tools backup-dynamodb backup.jsonl --prod
 *   2. tools normalize-case backup.jsonl transformed.jsonl
 *   3. tools nuke-dynamodb --prod --yes
 *   4. tools restore-dynamodb transformed.jsonl --prod --yes
 *
 * On hard collisions the transform aborts with a report and writes no
 * output — the operator picks a winner manually (by editing backup.jsonl
 * and re-running) before the rest of the pipeline can proceed.
 */
class NormalizeCase : CliktCommand(name = "normalize-case") {
    private val inputPath by argument(name = "input", help = "Source JSONL backup (from backup-dynamodb).")
    private val outputPath by argument(name = "output", help = "Destination JSONL for the transformed events.")
    private val force by option("--force", help = "Overwrite the output file if it already exists.").flag()
    private val dryRun by option("--dry-run", help = "Report what would change without writing the output file.").flag()

    override fun help(context: Context) =
        "Rewrite an event-log backup so every user/election/candidate/tier name uses the first-occurrence display case. Aborts on hard collisions."

    override fun run() {
        val input = Path.of(inputPath)
        if (!input.exists()) Output.error("Input file not found: $input")
        val output = Path.of(outputPath)
        if (!dryRun && output.exists() && !force) {
            Output.error("Output file already exists: $output (pass --force to overwrite)")
        }

        Output.banner("Normalizing case in $input")

        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val events = readNarratives(input, json)
        Output.step("Read ${events.size} event(s).")

        when (val result = NormalizeCaseTransform.transform(events)) {
            is NormalizeCaseTransform.Result.Collisions -> {
                Output.section("Collisions (transform aborted)")
                result.report.forEach { println("  $it") }
                Output.error(
                    "Found ${result.report.size} collision(s). Resolve by editing the input file " +
                        "(pick a winner for each pair, delete or merge the losing event) and re-run."
                )
            }
            is NormalizeCaseTransform.Result.Ok -> {
                Output.step("Rewrote ${result.rewrites} name reference(s).")
                if (dryRun) {
                    Output.success("Dry run complete; no output written.")
                    return
                }
                Files.newBufferedWriter(
                    output,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                ).use { writer ->
                    result.events.forEach { narrative ->
                        writer.write(json.encodeToString(narrative))
                        writer.newLine()
                    }
                }
                Output.success("Wrote ${result.events.size} event(s) to $output.")
            }
        }
    }

    private fun readNarratives(file: Path, json: Json): List<NarrativeEvent> {
        val result = mutableListOf<NarrativeEvent>()
        Files.newBufferedReader(file).useLines { lines ->
            lines.forEachIndexed { index, raw ->
                val line = raw.trim()
                if (line.isEmpty()) return@forEachIndexed
                try {
                    result.add(json.decodeFromString<NarrativeEvent>(line))
                } catch (e: Exception) {
                    Output.error("Failed to parse line ${index + 1} of $file: ${e.message}")
                }
            }
        }
        return result
    }
}
