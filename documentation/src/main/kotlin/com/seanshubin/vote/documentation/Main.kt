package com.seanshubin.vote.documentation

import com.seanshubin.vote.integration.database.DynamoDBDatabaseProvider
import com.seanshubin.vote.integration.database.InMemoryDatabaseProvider
import com.seanshubin.vote.integration.database.MySQLDatabaseProvider
import com.seanshubin.vote.integration.dsl.TestContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Usage: Main <output-directory>")
        return
    }

    val outputDir = File(args[0])
    outputDir.mkdirs()

    println("Generating documentation...")
    println("Output directory: ${outputDir.absolutePath}")
    println()

    // Generate index (first, so we know what files we're creating)
    println("📝 Generating index...")
    val indexHtml = IndexGenerator().generate()
    File(outputDir, "index.html").writeText(indexHtml)
    println("✓ Generated index.html")
    println()

    // Copy schema diagram if it exists
    println("📊 Copying schema diagram...")
    // Path is relative to project root, which is one level up from documentation module
    val schemaDiagramFile = File("../generated/schema-diagram/schema.html").canonicalFile
    if (schemaDiagramFile.exists()) {
        Files.copy(
            schemaDiagramFile.toPath(),
            File(outputDir, "schema.html").toPath(),
            StandardCopyOption.REPLACE_EXISTING
        )
        println("✓ Copied schema.html from schema-diagram module")
    } else {
        println("⚠ Schema diagram not found at: ${schemaDiagramFile.absolutePath}")
        println("  Run ./gradlew :schema-diagram:generateSchemaDiagram first")
    }
    println()

    // Generate scenario summary (using in-memory for fastest execution)
    println("📝 Generating scenario summary...")
    val inMemoryProvider = InMemoryDatabaseProvider()
    val eventLogContext = TestContext(inMemoryProvider)
    Scenario.comprehensive(eventLogContext)

    val scenarioHtml = ScenarioHtmlGenerator(inMemoryProvider.eventLog).generate()
    File(outputDir, "scenario.html").writeText(scenarioHtml)
    inMemoryProvider.close()
    println("✓ Generated scenario.html (${countLines(scenarioHtml)} lines)")
    println()

    // Generate MySQL dump
    println("🗄️  Generating MySQL dump...")
    println("  Starting MySQL container...")
    val mysqlProvider = MySQLDatabaseProvider()
    val mysqlContext = TestContext(mysqlProvider)
    Scenario.comprehensive(mysqlContext)

    val sqlHtml = SqlHtmlGenerator(mysqlProvider.connection).generate()
    File(outputDir, "sql.html").writeText(sqlHtml)
    mysqlProvider.close()
    println("✓ Generated sql.html (${countLines(sqlHtml)} lines)")
    println()

    // Generate DynamoDB dump
    println("☁️  Generating DynamoDB dump...")
    println("  Starting DynamoDB LocalStack container...")
    val dynamoDbProvider = DynamoDBDatabaseProvider()
    val dynamoDbContext = TestContext(dynamoDbProvider)
    Scenario.comprehensive(dynamoDbContext)

    val dynamoDbHtml = DynamoDbHtmlGenerator(dynamoDbProvider.dynamoDbClient).generate()
    File(outputDir, "dynamodb.html").writeText(dynamoDbHtml)
    dynamoDbProvider.close()
    println("✓ Generated dynamodb.html (${countLines(dynamoDbHtml)} lines)")
    println()

    // Generate event log and HTTP documentation with interleaved data
    println("🌐 Generating HTTP and event documentation...")
    println("  Starting HTTP server...")
    val documentationRecorder = DocumentationRecorder()
    val httpRecorder = HttpRecorder(documentationRecorder)
    httpRecorder.startServer()

    try {
        val httpBackend = HttpRecordingBackend(httpRecorder, documentationRecorder)
        val httpContext = TestContext(backend = httpBackend)
        Scenario.comprehensive(httpContext)

        // Generate events.html with HTTP context
        val eventsHtml = EventLogHtmlGeneratorWithHttp(documentationRecorder).generate()
        File(outputDir, "events.html").writeText(eventsHtml)
        println("✓ Generated events.html (${countLines(eventsHtml)} lines)")

        // Generate http.html with event context
        val httpHtml = HttpHtmlGeneratorWithEvents(documentationRecorder).generate()
        File(outputDir, "http.html").writeText(httpHtml)
        println("✓ Generated http.html (${countLines(httpHtml)} lines)")
    } finally {
        httpRecorder.stopServer()
    }
    println()

    println("✅ Documentation generation complete!")
    println("📂 View documentation at: file://${outputDir.absolutePath}/index.html")
}

private fun countLines(text: String): Int = text.lines().size
