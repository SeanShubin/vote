package com.seanshubin.vote.tools.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.seanshubin.vote.tools.lib.Docker
import com.seanshubin.vote.tools.lib.MysqlClient
import com.seanshubin.vote.tools.lib.Output
import com.seanshubin.vote.tools.lib.Procs
import com.seanshubin.vote.tools.lib.ProjectPaths
import kotlin.io.path.exists
import kotlin.io.path.readText

private const val MYSQL_CONTAINER = "vote-mysql"
private const val MYSQL_PORT = 3306
private const val MYSQL_ROOT_PASSWORD = "rootpass"

private const val DYNAMODB_CONTAINER = "vote-dynamodb"
private const val DYNAMODB_PORT = 8000

class DbSetupMysql : CliktCommand(name = "db-setup-mysql") {
    override fun help(context: Context) = "Start MySQL container and apply schema."

    override fun run() {
        Docker.requireAvailable()

        Output.banner("Setting up MySQL Database")

        if (Docker.containerExists(MYSQL_CONTAINER)) {
            if (Docker.containerRunning(MYSQL_CONTAINER)) {
                println("Container $MYSQL_CONTAINER is already running.")
            } else {
                println("Starting existing container $MYSQL_CONTAINER...")
                Docker.start(MYSQL_CONTAINER)
            }
        } else {
            println("Creating new MySQL container...")
            Docker.run(
                name = MYSQL_CONTAINER,
                image = "mysql:8.0",
                ports = mapOf(MYSQL_PORT to 3306),
                env = mapOf(
                    "MYSQL_ROOT_PASSWORD" to MYSQL_ROOT_PASSWORD,
                    "MYSQL_DATABASE" to MysqlClient.DATABASE,
                    "MYSQL_USER" to MysqlClient.USER,
                    "MYSQL_PASSWORD" to MysqlClient.PASSWORD
                ),
                imageArgs = listOf(
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_unicode_ci"
                )
            )
        }

        println("Waiting for MySQL to be ready...")
        val ready = Procs.waitUntil(timeoutSeconds = 60) {
            Docker.exec(
                MYSQL_CONTAINER,
                listOf(
                    "mysqladmin", "ping",
                    "-h", "localhost",
                    "-u", MysqlClient.USER,
                    "-p${MysqlClient.PASSWORD}",
                    "--silent"
                )
            ).success
        }
        if (!ready) Output.error("MySQL did not become ready in time.")
        Output.success("MySQL is ready!")

        val schemaFile = ProjectPaths.backendSchemaSql
        if (schemaFile.exists()) {
            println("Applying schema from ${ProjectPaths.projectRoot.relativize(schemaFile)}...")
            Docker.execOrFail(
                MYSQL_CONTAINER,
                listOf(
                    "mysql",
                    "-u${MysqlClient.USER}",
                    "-p${MysqlClient.PASSWORD}",
                    MysqlClient.DATABASE
                ),
                stdin = schemaFile.readText(),
                description = "Apply schema to MySQL"
            )
            Output.success("Schema applied successfully!")
        } else {
            Output.warn("Schema file not found at $schemaFile")
        }

        println()
        println("MySQL Database Ready")
        println("  Host:     ${MysqlClient.HOST}")
        println("  Port:     $MYSQL_PORT")
        println("  Database: ${MysqlClient.DATABASE}")
        println("  User:     ${MysqlClient.USER}")
        println("  Password: ${MysqlClient.PASSWORD}")
        println("  JDBC URL: jdbc:mysql://${MysqlClient.HOST}:$MYSQL_PORT/${MysqlClient.DATABASE}")
        println()
        println("To stop:   scripts/dev db-teardown-mysql")
        println("To reset:  scripts/dev db-reset-mysql")
    }
}

class DbResetMysql : CliktCommand(name = "db-reset-mysql") {
    override fun help(context: Context) = "Drop and recreate MySQL tables."

    override fun run() {
        Docker.requireAvailable()

        if (!Docker.containerRunning(MYSQL_CONTAINER)) {
            Output.error("Container $MYSQL_CONTAINER is not running. Run scripts/dev db-setup-mysql first.")
        }

        Output.banner("Resetting MySQL Database")

        println("Dropping all tables...")
        val dropSql = """
            SET FOREIGN_KEY_CHECKS = 0;
            DROP TABLE IF EXISTS event_log;
            DROP TABLE IF EXISTS ballots;
            DROP TABLE IF EXISTS eligible_voters;
            DROP TABLE IF EXISTS candidates;
            DROP TABLE IF EXISTS elections;
            DROP TABLE IF EXISTS users;
            DROP TABLE IF EXISTS sync_state;
            SET FOREIGN_KEY_CHECKS = 1;
        """.trimIndent()
        Docker.execOrFail(
            MYSQL_CONTAINER,
            listOf(
                "mysql",
                "-u${MysqlClient.USER}",
                "-p${MysqlClient.PASSWORD}",
                MysqlClient.DATABASE,
                "-e", dropSql
            ),
            description = "Drop MySQL tables"
        )
        println("Tables dropped.")

        val schemaFile = ProjectPaths.backendSchemaSql
        if (!schemaFile.exists()) Output.error("Schema file not found at $schemaFile")
        println("Applying schema from ${ProjectPaths.projectRoot.relativize(schemaFile)}...")
        Docker.execOrFail(
            MYSQL_CONTAINER,
            listOf("mysql", "-u${MysqlClient.USER}", "-p${MysqlClient.PASSWORD}", MysqlClient.DATABASE),
            stdin = schemaFile.readText(),
            description = "Apply schema to MySQL"
        )
        Output.success("MySQL database reset complete.")
    }
}

class DbTeardownMysql : CliktCommand(name = "db-teardown-mysql") {
    override fun help(context: Context) = "Stop and remove MySQL container."

    override fun run() {
        Docker.requireAvailable()
        Output.banner("Tearing down MySQL")

        if (Docker.containerRunning(MYSQL_CONTAINER)) {
            Docker.stop(MYSQL_CONTAINER)
            println("Container stopped.")
        } else {
            println("Container is not running.")
        }
        if (Docker.containerExists(MYSQL_CONTAINER)) {
            Docker.remove(MYSQL_CONTAINER)
            println("Container removed.")
        } else {
            println("Container does not exist.")
        }
        Output.success("MySQL teardown complete.")
    }
}

class DbSetupDynamodb : CliktCommand(name = "db-setup-dynamodb") {
    override fun help(context: Context) = "Start DynamoDB Local container and create tables."

    override fun run() {
        Docker.requireAvailable()

        Output.banner("Setting up DynamoDB Local (Single-Table)")

        if (Docker.containerRunning(DYNAMODB_CONTAINER)) {
            println("DynamoDB Local container already running.")
        } else if (Docker.containerExists(DYNAMODB_CONTAINER)) {
            println("Starting existing DynamoDB Local container...")
            Docker.start(DYNAMODB_CONTAINER)
        } else {
            println("Creating and starting DynamoDB Local container...")
            Docker.run(
                name = DYNAMODB_CONTAINER,
                image = "amazon/dynamodb-local",
                ports = mapOf(DYNAMODB_PORT to 8000)
            )
        }

        println("Waiting for DynamoDB Local to be ready...")
        val ready = Procs.waitUntil(timeoutSeconds = 30) {
            runBlocking { DynamoTables.awaitReady() }
            true
        }
        if (!ready) Output.error("DynamoDB Local did not become ready in time.")

        println("Creating single-table schema...")
        runBlocking {
            DynamoTables.ensureCreated()
        }
        Output.success("Tables created.")

        println()
        println("DynamoDB Local: http://localhost:$DYNAMODB_PORT")
        println("Tables:")
        println("  - vote_data       (single table with PK/SK design)")
        println("  - vote_event_log  (event sourcing)")
        println()
        println("To stop:    scripts/dev db-teardown-dynamodb")
        println("To inspect: scripts/dev inspect-dynamodb-all")
    }
}

class DbResetDynamodb : CliktCommand(name = "db-reset-dynamodb") {
    override fun help(context: Context) = "Teardown and rebuild DynamoDB Local."

    override fun run() {
        DbTeardownDynamodb().run()
        DbSetupDynamodb().run()
    }
}

class DbTeardownDynamodb : CliktCommand(name = "db-teardown-dynamodb") {
    override fun help(context: Context) = "Stop and remove DynamoDB Local container."

    override fun run() {
        Docker.requireAvailable()
        Output.banner("Tearing down DynamoDB Local")
        Docker.stop(DYNAMODB_CONTAINER)
        Docker.remove(DYNAMODB_CONTAINER)
        Output.success("DynamoDB Local stopped and removed.")
    }
}

class DbInitDynamodb : CliktCommand(name = "db-init-dynamodb") {
    override fun help(context: Context) = "Show information about DynamoDB table creation."

    override fun run() {
        println("DynamoDB tables are created automatically by db-setup-dynamodb.")
        println("To list tables manually:")
        println("  aws dynamodb list-tables --endpoint-url http://localhost:$DYNAMODB_PORT --region us-east-1")
    }
}

class DbSetup : CliktCommand(name = "db-setup") {
    override fun help(context: Context) = "Start local databases (DynamoDB Local + H2)."

    override fun run() {
        println("H2 starts automatically with the backend; nothing to start here.")
        println("For DynamoDB Local, run: scripts/dev db-setup-dynamodb")
        println("For MySQL, run:          scripts/dev db-setup-mysql")
    }
}

class DbTeardown : CliktCommand(name = "db-teardown") {
    override fun help(context: Context) = "Stop local databases."

    override fun run() {
        println("H2 shuts down with the backend; nothing to stop here.")
        println("For DynamoDB Local, run: scripts/dev db-teardown-dynamodb")
        println("For MySQL, run:          scripts/dev db-teardown-mysql")
    }
}

class DbReset : CliktCommand(name = "db-reset") {
    override fun help(context: Context) = "Reset local databases to clean state."

    override fun run() {
        println("Reset the database backing your current backend:")
        println("  scripts/dev db-reset-dynamodb")
        println("  scripts/dev db-reset-mysql")
    }
}

class PurgeMysql : CliktCommand(name = "purge-mysql") {
    override fun help(context: Context) = "Purge MySQL database (alias for db-reset-mysql)."

    override fun run() {
        Output.banner("Purging MySQL Database")
        DbResetMysql().run()
        Output.success("MySQL database purged and recreated.")
    }
}

class PurgeDynamodb : CliktCommand(name = "purge-dynamodb") {
    override fun help(context: Context) = "Purge DynamoDB tables (alias for db-reset-dynamodb)."

    override fun run() {
        Output.banner("Purging DynamoDB Tables")
        DbResetDynamodb().run()
        Output.success("DynamoDB tables purged and recreated.")
    }
}

private fun runBlocking(block: suspend () -> Unit) =
    kotlinx.coroutines.runBlocking { block() }
