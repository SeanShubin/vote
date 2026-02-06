package com.seanshubin.vote.schema

object GraphVizGenerator {
    fun generate(schema: Schema): String {
        val sb = StringBuilder()

        sb.appendLine("digraph schema {")
        sb.appendLine("    rankdir=LR;")
        sb.appendLine("    node [shape=record, fontname=\"Helvetica\"];")
        sb.appendLine("    edge [fontname=\"Helvetica\", fontsize=10];")
        sb.appendLine()

        // Generate nodes (tables)
        for (table in schema.tables) {
            sb.append("    ${table.name} [label=\"{")
            sb.append("${table.name}|")

            val columnLabels = table.columns.map { column ->
                val pk = if (column.isPrimaryKey) " (PK)" else ""
                val notNull = if (column.notNull) " NOT NULL" else ""
                "${column.name}: ${column.type}$pk$notNull"
            }

            sb.append(columnLabels.joinToString("\\l") + "\\l")
            sb.appendLine("}\"];")
        }

        sb.appendLine()

        // Generate edges (foreign keys)
        for (table in schema.tables) {
            for (fk in table.foreignKeys) {
                sb.append("    ${table.name} -> ${fk.referencesTable}")
                sb.append(" [label=\"${fk.column}\"]")
                sb.appendLine(";")
            }
        }

        sb.appendLine("}")

        return sb.toString()
    }
}
