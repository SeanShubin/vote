package com.seanshubin.vote.tools.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.seanshubin.vote.tools.lib.MarkdownTablePadder
import com.seanshubin.vote.tools.lib.ProjectPaths
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.system.exitProcess

class PadTables : CliktCommand(name = "pad-tables") {
    override fun help(context: Context) =
        "Pad markdown tables in .md files so columns align. With no paths, walks --root recursively."

    private val rootOpt: String? by option(
        "--root",
        help = "Directory to walk for .md files. Defaults to project root. Ignored when paths are given."
    )

    private val check: Boolean by option(
        "--check",
        help = "Report files that would change without writing. Exit non-zero if any."
    ).flag()

    private val paths: List<String> by argument(
        name = "paths",
        help = "Specific .md files to process. If omitted, walks --root."
    ).multiple()

    override fun run() {
        val targets = collectTargets()
        val changed = mutableListOf<Path>()

        for (path in targets) {
            val original = path.readText(Charsets.UTF_8)
            val padded = MarkdownTablePadder.padContent(original)
            if (padded != original) {
                changed.add(path)
                if (!check) path.writeText(padded, Charsets.UTF_8)
            }
        }

        val rootForRel = rootOpt?.let { Path.of(it).absolute().normalize() }
            ?: ProjectPaths.projectRoot
        val verb = if (check) "would change" else "padded"
        if (changed.isEmpty()) {
            println("All markdown tables are already padded.")
        } else {
            for (p in changed) {
                val rel = runCatching { rootForRel.relativize(p).toString() }.getOrDefault(p.toString())
                println("  $verb: $rel")
            }
            println()
            println("${changed.size} file(s) $verb.")
        }

        if (check && changed.isNotEmpty()) exitProcess(1)
    }

    private fun collectTargets(): List<Path> {
        if (paths.isNotEmpty()) {
            return paths.map { Path.of(it).absolute().normalize() }
                .filter { it.isRegularFile() && it.extension == "md" }
        }
        val root = rootOpt?.let { Path.of(it).absolute().normalize() } ?: ProjectPaths.projectRoot
        if (!root.isDirectory()) return emptyList()
        return walk(root)
    }

    private fun walk(root: Path): List<Path> {
        val out = mutableListOf<Path>()
        Files.walk(root).use { stream ->
            stream.forEach { path ->
                if (path.isDirectory()) return@forEach
                if (path.extension != "md") return@forEach
                if (isExcluded(root, path)) return@forEach
                out.add(path)
            }
        }
        return out.sorted()
    }

    private fun isExcluded(root: Path, path: Path): Boolean {
        val rel = runCatching { root.relativize(path) }.getOrNull() ?: return false
        for (i in 0 until rel.nameCount - 1) {
            val name = rel.getName(i).name
            if (name.startsWith(".")) return true
            if (name in EXCLUDED_DIRS) return true
        }
        return false
    }

    companion object {
        private val EXCLUDED_DIRS = setOf(
            "build",
            "generated",
            "kotlin-js-store",
            "node_modules",
            "target"
        )
    }
}
