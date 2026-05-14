package com.seanshubin.vote.tools.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.seanshubin.vote.backend.repository.DynamoDbSingleTableQueryModel
import com.seanshubin.vote.backend.repository.MySqlQueryModel
import com.seanshubin.vote.backend.repository.QueryLoaderFromResource
import com.seanshubin.vote.contract.QueryModel
import com.seanshubin.vote.domain.Ranking
import com.seanshubin.vote.domain.RankingKind
import com.seanshubin.vote.domain.buildBallotText
import com.seanshubin.vote.tools.lib.DynamoClient
import com.seanshubin.vote.tools.lib.MysqlClient
import com.seanshubin.vote.tools.lib.Output
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * Dump every voter's ballot from the configured backend to a single text
 * file, using the same rendering as the frontend's "Copy ballot as text"
 * button (see [buildBallotText]).
 *
 * Reads the projection directly via the repository's [QueryModel] — no auth
 * required, since this is an admin/dev tool running on the same box as the
 * database. Matches the inspect/backup tooling convention.
 */
class DownloadBallots : CliktCommand(name = "download-ballots") {
    override fun help(context: Context) =
        "Dump every voter's ballot to a single text file."

    private val database: String by argument(name = "DATABASE", help = "mysql or dynamodb")
    private val outputPath: String by argument(name = "FILE", help = "Path to write the ballots text file.")
    private val prod: Boolean by option("--prod", help = "DynamoDB only: target real AWS instead of DynamoDB Local.").flag()

    override fun run() {
        if (database !in setOf("mysql", "dynamodb")) {
            Output.error("Database must be 'mysql' or 'dynamodb', got '$database'.")
        }
        val file = Path.of(outputPath)
        when (database) {
            "mysql" -> withMysql { dump(it, file) }
            "dynamodb" -> withDynamodb(prod) { dump(it, file) }
        }
    }

    private fun dump(queryModel: QueryModel, file: Path) {
        val elections = queryModel.listElections().sortedBy { it.electionName }
        Output.banner("Dumping ballots from ${elections.size} election(s)")

        val total = Files.newBufferedWriter(file).use { writer ->
            var written = 0
            elections.forEach { election ->
                val tiers = queryModel.listTiers(election.electionName).toSet()
                // MySQL's rankings table doesn't preserve the kind tag, so
                // entries come back uniformly as CANDIDATE; re-tag from the
                // tier list before rendering so tier headings appear.
                // DynamoDB stores rankings as a serialized blob and keeps
                // kind, but reapplying is idempotent.
                val ballots = queryModel.listBallots(election.electionName).sortedBy { it.voterName }
                println("Election '${election.electionName}': ${ballots.size} ballot(s)")
                ballots.forEach { ballot ->
                    val tagged = ballot.rankings.map { it.withKindFromTiers(tiers) }
                    if (written > 0) writer.write("\n\n")
                    writer.write(buildBallotText(election.electionName, ballot.voterName, tagged))
                    written++
                }
            }
            written
        }

        Output.success("Wrote $total ballot(s) to $outputPath")
    }

    private fun withMysql(block: (QueryModel) -> Unit) {
        MysqlClient.connect().use { connection ->
            val queryModel = MySqlQueryModel(connection, QueryLoaderFromResource(), Json)
            block(queryModel)
        }
    }

    private fun withDynamodb(prod: Boolean, block: (QueryModel) -> Unit) {
        DynamoClient.createFor(prod).use { client ->
            val queryModel = DynamoDbSingleTableQueryModel(client, Json)
            block(queryModel)
        }
    }

    private fun Ranking.withKindFromTiers(tiers: Set<String>): Ranking {
        val expectedKind = if (candidateName in tiers) RankingKind.TIER else RankingKind.CANDIDATE
        return if (kind == expectedKind) this else copy(kind = expectedKind)
    }
}
