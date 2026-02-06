package com.seanshubin.vote.schema

import java.io.File

fun main(args: Array<String>) {
    if (args.size != 2) {
        println("Usage: schema-diagram <input-schema.sql> <output-directory>")
        println("Example: schema-diagram schema.sql generated/schema-diagram")
        return
    }

    val inputFile = File(args[0])
    val outputDir = File(args[1])

    if (!inputFile.exists()) {
        println("Error: Input file not found: ${inputFile.absolutePath}")
        return
    }

    println("Reading schema from: ${inputFile.absolutePath}")
    val sql = inputFile.readText()

    println("Parsing SQL schema...")
    val schema = SqlSchemaParser.parse(sql)

    println("Found ${schema.tables.size} tables:")
    for (table in schema.tables) {
        println("  - ${table.name} (${table.columns.size} columns, ${table.foreignKeys.size} foreign keys)")
    }

    // Create output directory
    outputDir.mkdirs()

    // Generate GraphViz .dot file
    println("Generating GraphViz diagram...")
    val dotContent = GraphVizGenerator.generate(schema)
    val dotFile = File(outputDir, "schema.dot")
    dotFile.writeText(dotContent)
    println("✓ Written: ${dotFile.absolutePath}")

    // Generate Mermaid diagram
    println("Generating Mermaid diagram...")
    val mermaidContent = MermaidGenerator.generate(schema)
    val mermaidFile = File(outputDir, "schema.mmd")
    mermaidFile.writeText(mermaidContent)
    println("✓ Written: ${mermaidFile.absolutePath}")

    // Generate HTML table (with embedded SVG if available)
    println("Generating HTML table...")
    val svgFile = File(outputDir, "schema.svg")
    val htmlContent = HtmlTableGenerator.generate(schema, if (svgFile.exists()) svgFile else null)
    val htmlFile = File(outputDir, "schema.html")
    htmlFile.writeText(htmlContent)
    println("✓ Written: ${htmlFile.absolutePath}")

    println("\nSchema diagram generation complete!")
    println("Output directory: ${outputDir.absolutePath}")
}
