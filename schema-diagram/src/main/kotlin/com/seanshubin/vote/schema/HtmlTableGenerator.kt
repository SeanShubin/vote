package com.seanshubin.vote.schema

import java.io.File

object HtmlTableGenerator {
    fun generate(schema: Schema, svgFile: File? = null): String {
        val sb = StringBuilder()

        sb.appendLine("<!DOCTYPE html>")
        sb.appendLine("<html>")
        sb.appendLine("<head>")
        sb.appendLine("    <meta charset=\"UTF-8\">")
        sb.appendLine("    <title>Database Schema</title>")
        sb.appendLine("    <style>")
        sb.appendLine("        body { font-family: Arial, sans-serif; margin: 20px; }")
        sb.appendLine("        h1 { color: #333; }")
        sb.appendLine("        table { border-collapse: collapse; width: 100%; margin-bottom: 30px; }")
        sb.appendLine("        th, td { border: 1px solid #ddd; padding: 12px; text-align: left; }")
        sb.appendLine("        th { background-color: #4CAF50; color: white; }")
        sb.appendLine("        tr:nth-child(even) { background-color: #f2f2f2; }")
        sb.appendLine("        .pk { color: #d32f2f; font-weight: bold; }")
        sb.appendLine("        .fk { color: #1976d2; }")
        sb.appendLine("        .table-name { font-size: 1.5em; margin-top: 20px; color: #333; }")
        sb.appendLine("    </style>")
        sb.appendLine("</head>")
        sb.appendLine("<body>")
        sb.appendLine("    <h1>Database Schema</h1>")

        // Embed SVG diagram if available
        if (svgFile != null && svgFile.exists()) {
            sb.appendLine("    <div>")
            val svgContent = svgFile.readText()
            sb.appendLine(svgContent)
            sb.appendLine("    </div>")
        }

        for (table in schema.tables) {
            sb.appendLine("    <div class=\"table-name\">${table.name}</div>")
            sb.appendLine("    <table>")
            sb.appendLine("        <thead>")
            sb.appendLine("            <tr>")
            sb.appendLine("                <th>Column</th>")
            sb.appendLine("                <th>Type</th>")
            sb.appendLine("                <th>Constraints</th>")
            sb.appendLine("            </tr>")
            sb.appendLine("        </thead>")
            sb.appendLine("        <tbody>")

            for (column in table.columns) {
                val constraints = mutableListOf<String>()
                if (column.isPrimaryKey) constraints.add("<span class=\"pk\">PRIMARY KEY</span>")
                if (table.foreignKeys.any { it.column == column.name }) {
                    val fk = table.foreignKeys.first { it.column == column.name }
                    constraints.add("<span class=\"fk\">FK → ${fk.referencesTable}(${fk.referencesColumn})</span>")
                }
                if (column.notNull) constraints.add("NOT NULL")

                sb.appendLine("            <tr>")
                sb.appendLine("                <td>${column.name}</td>")
                sb.appendLine("                <td>${column.type}</td>")
                sb.appendLine("                <td>${constraints.joinToString(", ")}</td>")
                sb.appendLine("            </tr>")
            }

            sb.appendLine("        </tbody>")
            sb.appendLine("    </table>")
        }

        sb.appendLine("</body>")
        sb.appendLine("</html>")

        return sb.toString()
    }
}
