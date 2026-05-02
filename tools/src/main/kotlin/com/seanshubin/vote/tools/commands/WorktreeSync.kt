package com.seanshubin.vote.tools.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.seanshubin.vote.tools.lib.ProjectPaths
import com.seanshubin.vote.tools.lib.Procs
import java.nio.file.Path
import kotlin.io.path.Path as toPath

class WorktreeSync : CliktCommand(name = "worktree-sync") {
    override fun help(context: Context) =
        "For each auxiliary git worktree: notify if it has anything master doesn't yet have, " +
            "fast-forward (git reset --hard master) if it's already fully merged but lagging, " +
            "skip if it's already at master."

    override fun run() {
        val masterCommit = Procs.run("git", "rev-parse", "--verify", "master", workingDir = ProjectPaths.projectRoot)
        if (!masterCommit.success) {
            System.err.println("ERROR: cannot resolve 'master' ref. Is this repo using a different default branch?")
            kotlin.system.exitProcess(1)
        }
        val masterSha = masterCommit.stdout.trim()

        val worktrees = listWorktrees()
        if (worktrees.isEmpty()) {
            println("No worktrees registered.")
            return
        }

        val mainPath = worktrees.first().path
        println("Main worktree: $mainPath (skipped -- never reset)")
        println()

        val auxiliary = worktrees.drop(1)
        if (auxiliary.isEmpty()) {
            println("No auxiliary worktrees to sync.")
            return
        }

        for (wt in auxiliary) {
            handleWorktree(wt, masterSha)
        }
    }

    private fun handleWorktree(wt: Worktree, masterSha: String) {
        val label = wt.path.fileName?.toString() ?: wt.path.toString()
        print("[$label] ")

        when {
            wt.prunable -> {
                println("SKIP -- prunable (directory missing on disk; run 'git worktree prune' to clean up)")
                return
            }
            wt.locked -> {
                println("SKIP -- locked${wt.lockReason?.let { " ($it)" } ?: ""}")
                return
            }
            wt.detached -> {
                println("NOTIFY -- detached HEAD; not on a branch, cannot compare to master")
                return
            }
        }

        val branch = wt.branch ?: run {
            println("NOTIFY -- no branch info from git worktree list")
            return
        }

        // Working-tree state inside the worktree.
        val statusResult = Procs.run("git", "status", "--porcelain", workingDir = wt.path)
        if (!statusResult.success) {
            println("NOTIFY -- failed to read 'git status' (exit ${statusResult.exitCode})")
            return
        }
        val dirty = statusResult.stdout.trim().isNotEmpty()
        if (dirty) {
            val lineCount = statusResult.stdout.trim().lines().size
            println("NOTIFY -- uncommitted/untracked changes ($lineCount entries; run 'git status' in $label)")
            return
        }

        // Stashes are scoped per-worktree as of Git 2.43; check just this one.
        val stashResult = Procs.run("git", "stash", "list", workingDir = wt.path)
        if (stashResult.success) {
            val stashLines = stashResult.stdout.trim().lines().filter { it.isNotBlank() }
            if (stashLines.isNotEmpty()) {
                println("NOTIFY -- ${stashLines.size} stash entr${if (stashLines.size == 1) "y" else "ies"} (run 'git stash list' in $label)")
                return
            }
        }

        // Branch state vs. master.
        val branchSha = wt.head
        if (branchSha == masterSha) {
            println("OK -- already at master ($masterSha)")
            return
        }

        // Are there commits on the branch that master doesn't have?
        val unmerged = Procs.run("git", "rev-list", "$masterSha..$branchSha", workingDir = wt.path)
        if (!unmerged.success) {
            println("NOTIFY -- failed to compute rev-list (exit ${unmerged.exitCode})")
            return
        }
        val unmergedCommits = unmerged.stdout.trim().lines().filter { it.isNotBlank() }
        if (unmergedCommits.isNotEmpty()) {
            println("NOTIFY -- ${unmergedCommits.size} commit${if (unmergedCommits.size == 1) "" else "s"} on $branch not in master (deal with these before piling on new work)")
            return
        }

        // Branch is strictly behind master and clean → fast-forward via reset.
        val reset = Procs.run("git", "reset", "--hard", "master", workingDir = wt.path)
        if (!reset.success) {
            println("ERROR -- git reset --hard master failed (exit ${reset.exitCode}): ${reset.stderr.trim()}")
            return
        }
        println("RESET -- caught up to master (was $branchSha, now $masterSha)")
    }

    /**
     * One record per worktree from `git worktree list --porcelain`.
     *
     * Porcelain format: a paragraph per worktree, attribute-per-line.
     * - "worktree <path>" is always first
     * - "HEAD <sha>" is always present (even on bare/detached)
     * - either "branch refs/heads/<name>" OR "detached" is present
     * - "locked [reason]" appears when locked
     * - "prunable [reason]" appears when the directory is missing
     */
    private fun listWorktrees(): List<Worktree> {
        val porcelain = Procs.runOrFail(
            "git", "worktree", "list", "--porcelain",
            workingDir = ProjectPaths.projectRoot,
            description = "git worktree list",
        )

        val records = mutableListOf<Worktree>()
        var path: Path? = null
        var head: String? = null
        var branch: String? = null
        var locked = false
        var lockReason: String? = null
        var prunable = false
        var detached = false

        fun flush() {
            val p = path ?: return
            records.add(
                Worktree(
                    path = p,
                    head = head ?: "",
                    branch = branch,
                    locked = locked,
                    lockReason = lockReason,
                    prunable = prunable,
                    detached = detached,
                )
            )
            path = null
            head = null
            branch = null
            locked = false
            lockReason = null
            prunable = false
            detached = false
        }

        for (raw in porcelain.lineSequence()) {
            val line = raw.trimEnd()
            if (line.isEmpty()) {
                flush()
                continue
            }
            val (key, rest) = line.split(' ', limit = 2).let { it[0] to it.getOrNull(1) }
            when (key) {
                "worktree" -> {
                    flush()
                    path = rest?.let { toPath(it) }
                }
                "HEAD" -> head = rest
                "branch" -> branch = rest?.removePrefix("refs/heads/")
                "detached" -> detached = true
                "locked" -> {
                    locked = true
                    lockReason = rest?.takeIf { it.isNotBlank() }
                }
                "prunable" -> prunable = true
            }
        }
        flush()
        return records
    }

    private data class Worktree(
        val path: Path,
        val head: String,
        val branch: String?,
        val locked: Boolean,
        val lockReason: String?,
        val prunable: Boolean,
        val detached: Boolean,
    )
}
