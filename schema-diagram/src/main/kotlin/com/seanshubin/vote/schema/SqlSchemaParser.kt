package com.seanshubin.vote.schema

data class Schema(
    val tables: List<Table>
)

data class Table(
    val name: String,
    val columns: List<Column>,
    val primaryKey: PrimaryKey?,
    val foreignKeys: List<ForeignKey>
)

data class Column(
    val name: String,
    val type: String,
    val notNull: Boolean,
    val isPrimaryKey: Boolean
)

data class PrimaryKey(
    val columns: List<String>
)

data class ForeignKey(
    val column: String,
    val referencesTable: String,
    val referencesColumn: String,
    val onDelete: String?
)

object SqlSchemaParser {
    fun parse(sql: String): Schema {
        val tables = mutableListOf<Table>()

        // Split into individual statements first
        val statements = sql.split(";").filter { it.contains("CREATE TABLE", ignoreCase = true) }

        for (statement in statements) {
            // Match CREATE TABLE statement
            val tablePattern = """CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?(\w+)\s*\((.*)\)""".toRegex(
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
            )

            val match = tablePattern.find(statement) ?: continue
            val tableName = match.groupValues[1]
            val tableBody = match.groupValues[2]

            val columns = mutableListOf<Column>()
            val foreignKeys = mutableListOf<ForeignKey>()
            var primaryKey: PrimaryKey? = null

            // Split table body by commas (simplified - doesn't handle commas in nested structures)
            val lines = tableBody.split(",").map { it.trim() }

            for (line in lines) {
                val lineUpper = line.uppercase().trim()

                // Skip MySQL-specific lines
                if (lineUpper.startsWith("INDEX ") ||
                    lineUpper.startsWith("CHECK ") ||
                    lineUpper.startsWith("ENGINE") ||
                    lineUpper.startsWith("DEFAULT CHARSET") ||
                    lineUpper.isEmpty()) {
                    continue
                }

                when {
                    // Match column definitions
                    line.matches("""^\w+\s+\w+.*""".toRegex()) &&
                    !lineUpper.startsWith("PRIMARY KEY") &&
                    !lineUpper.startsWith("FOREIGN KEY") -> {
                        val columnPattern = """^(\w+)\s+(\w+(?:\(\d+\))?)(.*)""".toRegex()
                        val columnMatch = columnPattern.find(line)
                        if (columnMatch != null) {
                            val columnName = columnMatch.groupValues[1]
                            val columnType = columnMatch.groupValues[2]
                            val constraints = columnMatch.groupValues[3].uppercase()

                            val notNull = constraints.contains("NOT NULL")
                            val isPrimaryKey = constraints.contains("PRIMARY KEY")

                            columns.add(Column(columnName, columnType, notNull, isPrimaryKey))

                            if (isPrimaryKey) {
                                primaryKey = PrimaryKey(listOf(columnName))
                            }
                        }
                    }

                    // Match PRIMARY KEY constraint
                    line.uppercase().startsWith("PRIMARY KEY") -> {
                        val pkPattern = """PRIMARY\s+KEY\s*\((.*?)\)""".toRegex(RegexOption.IGNORE_CASE)
                        val pkMatch = pkPattern.find(line)
                        if (pkMatch != null) {
                            val pkColumns = pkMatch.groupValues[1].split(",").map { it.trim() }
                            primaryKey = PrimaryKey(pkColumns)

                            // Mark columns as primary key
                            val updatedColumns = columns.map { col ->
                                if (pkColumns.contains(col.name)) {
                                    col.copy(isPrimaryKey = true)
                                } else {
                                    col
                                }
                            }
                            columns.clear()
                            columns.addAll(updatedColumns)
                        }
                    }

                    // Match FOREIGN KEY constraint
                    line.uppercase().startsWith("FOREIGN KEY") -> {
                        val fkPattern = """FOREIGN\s+KEY\s*\((\w+)\)\s*REFERENCES\s+(\w+)\s*\((\w+)\)(?:\s+ON\s+DELETE\s+(\w+(?:\s+\w+)?))?""".toRegex(
                            RegexOption.IGNORE_CASE
                        )
                        val fkMatch = fkPattern.find(line)
                        if (fkMatch != null) {
                            foreignKeys.add(
                                ForeignKey(
                                    column = fkMatch.groupValues[1],
                                    referencesTable = fkMatch.groupValues[2],
                                    referencesColumn = fkMatch.groupValues[3],
                                    onDelete = fkMatch.groupValues.getOrNull(4)?.takeIf { it.isNotBlank() }
                                )
                            )
                        }
                    }
                }
            }

            tables.add(Table(tableName, columns, primaryKey, foreignKeys))
        }

        return Schema(tables)
    }
}
