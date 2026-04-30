package com.seanshubin.vote.tools.lib

object MarkdownTablePadder {
    private const val MIN_COL_WIDTH = 3

    fun padContent(content: String): String {
        val lines = content.split('\n')
        val out = ArrayList<String>(lines.size)
        var i = 0
        while (i < lines.size) {
            if (parseRow(lines[i]) != null) {
                val block = ArrayList<String>()
                while (i < lines.size && parseRow(lines[i]) != null) {
                    block.add(lines[i])
                    i++
                }
                if (block.size >= 2 && isSeparator(parseRow(block[1])!!)) {
                    out.addAll(padTable(block))
                } else {
                    out.addAll(block)
                }
            } else {
                out.add(lines[i])
                i++
            }
        }
        return out.joinToString("\n")
    }

    fun parseRow(line: String): List<String>? {
        val trimmed = line.trim()
        if (trimmed.length < 2 || !trimmed.startsWith('|') || !trimmed.endsWith('|')) return null
        val inner = trimmed.substring(1, trimmed.length - 1)
        return inner.split('|').map { it.trim() }
    }

    fun isSeparator(cells: List<String>): Boolean {
        if (cells.isEmpty()) return false
        return cells.all { isSeparatorCell(it) }
    }

    private fun isSeparatorCell(cell: String): Boolean {
        if (cell.isEmpty()) return false
        var idx = 0
        if (cell[idx] == ':') idx++
        if (idx >= cell.length || cell[idx] != '-') return false
        var sawColon = false
        while (idx < cell.length) {
            val ch = cell[idx]
            if (sawColon) return false
            when (ch) {
                '-' -> {}
                ':' -> sawColon = true
                else -> return false
            }
            idx++
        }
        return true
    }

    fun formatSeparatorCell(original: String, width: Int): String {
        val left = original.startsWith(':')
        val right = original.endsWith(':')
        val colonWidth = (if (left) 1 else 0) + (if (right) 1 else 0)
        val dashCount = if (width > colonWidth) width - colonWidth else 1
        val sb = StringBuilder(width)
        if (left) sb.append(':')
        repeat(dashCount) { sb.append('-') }
        if (right) sb.append(':')
        return sb.toString()
    }

    fun padTable(lines: List<String>): List<String> {
        val rows = lines.map { parseRow(it) ?: return lines }
        if (rows.size < 2) return lines

        val maxCols = rows.maxOf { it.size }
        val normalized = rows.map { row ->
            if (row.size < maxCols) row + List(maxCols - row.size) { "" } else row
        }

        val widths = IntArray(maxCols)
        normalized.forEachIndexed { i, row ->
            if (i == 1 && isSeparator(row)) return@forEachIndexed
            row.forEachIndexed { j, cell ->
                val w = visualWidth(cell)
                if (w > widths[j]) widths[j] = w
            }
        }
        for (j in widths.indices) {
            if (widths[j] < MIN_COL_WIDTH) widths[j] = MIN_COL_WIDTH
        }

        return normalized.mapIndexed { i, row ->
            val sb = StringBuilder()
            sb.append('|')
            if (i == 1 && isSeparator(row)) {
                row.forEachIndexed { j, cell ->
                    sb.append(' ')
                    sb.append(formatSeparatorCell(cell, widths[j]))
                    sb.append(' ')
                    sb.append('|')
                }
            } else {
                row.forEachIndexed { j, cell ->
                    sb.append(' ')
                    sb.append(cell)
                    val padding = widths[j] - visualWidth(cell)
                    repeat(padding) { sb.append(' ') }
                    sb.append(' ')
                    sb.append('|')
                }
            }
            sb.toString()
        }
    }

    private fun visualWidth(s: String): Int = s.codePointCount(0, s.length)
}
