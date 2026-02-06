package com.seanshubin.vote.schema

object MermaidGenerator {
    fun generate(schema: Schema): String {
        val sb = StringBuilder()

        sb.appendLine("erDiagram")

        // Build relationship map
        val relationships = mutableMapOf<Pair<String, String>, MutableList<String>>()

        for (table in schema.tables) {
            for (fk in table.foreignKeys) {
                val key = table.name to fk.referencesTable
                relationships.getOrPut(key) { mutableListOf() }.add(fk.column)
            }
        }

        // Generate relationships
        for ((pair, columns) in relationships) {
            val (fromTable, toTable) = pair
            val label = columns.joinToString(", ")
            // Using ||--o{ for one-to-many relationship
            sb.appendLine("    $toTable ||--o{ $fromTable : \"$label\"")
        }

        sb.appendLine()

        // Generate table definitions
        for (table in schema.tables) {
            sb.appendLine("    ${table.name} {")

            for (column in table.columns) {
                val pk = if (column.isPrimaryKey) " PK" else ""
                val fk = if (table.foreignKeys.any { it.column == column.name }) " FK" else ""
                val notNull = if (column.notNull && !column.isPrimaryKey) " NOT_NULL" else ""

                sb.appendLine("        ${column.type} ${column.name}$pk$fk$notNull")
            }

            sb.appendLine("    }")
        }

        return sb.toString()
    }
}
