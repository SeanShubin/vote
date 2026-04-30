# `git stash create` → `git stash apply` wipes the index

## Symptom

A commit completes without errors, but `git ls-tree -r --name-only HEAD | wc -l` returns `0` and `git diff HEAD~1 HEAD --shortstat` shows "X deletions" with zero insertions. The commit looks like "delete every tracked file" even though the user staged a small set of normal changes. Two commits on `master` (`7711d90` and a later in-session reproduction) hit this; both had to be reverted.

## Root cause

`git stash create` and `git stash apply` are not safe to use back-to-back when the index has staged changes that match the working tree. On Git for Windows **2.24.1** (December 2019), the sequence wipes the index.

Six-line reproducer in a fresh repo:

```sh
git init && git commit --allow-empty -m base
echo a > a && git add a && git commit -m a
echo b > a && git add a               # stage one modification
sref=$(git stash create)              # snapshot, no side effect
git stash apply "$sref"               # ← wipes the index
git ls-files --stage                  # (empty)
```

`git stash create` snapshots `(HEAD, index_tree, worktree_tree)` into a commit object **without touching the index or working tree**. So when `git stash apply` runs immediately afterward, the stash's working-tree state and the live working tree are identical. The internal 3-way merge in `apply` (base = HEAD, ours = current state, theirs = stash's W) ends in a degenerate result; the cleanup step that's supposed to leave the index in a sane state instead leaves it empty.

Tracing `git stash apply` shows the relevant subcommands:

```
git diff-index --cached --name-only --diff-filter=A <stash-W-tree>
git update-index --add --stdin
```

The first finds files "added in the current index but not in the stash's working tree" — meant to preserve any net-new files the user introduced on top of the stash. The second re-stages them. When the stash's W matches the index exactly, the diff produces zero output, the merge in between leaves the index empty, and the re-stage adds nothing.

## What the variations proved

| Variation                                | Result                                       |
| ---------------------------------------- | -------------------------------------------- |
| `create` + `apply` (no `--index`)        | index → 0                                    |
| `create` + `apply --index`               | index → 0 (same bug)                         |
| `create` + `checkout -- .` + `apply`     | index → 0 (checkout was a red herring)       |
| `create` + `apply` with index ≠ worktree | aborts with merge error — refuses, no damage |
| `stash push --keep-index` + `pop`        | works correctly                              |

`--index` does not help. The intervening `git checkout -- .` is irrelevant. The bug is purely in the `create` → `apply` shape.

## Why `git stash push --keep-index` works

`push` *actually moves* changes out of the working tree, leaving the worktree showing only the staged content. There is real space for `apply`/`pop` to put the unstaged delta back. The standard "format-on-commit" pattern uses `push --keep-index` for this reason.

## Where this hit us

Pre-commit hook in `scripts/git-hooks/pre-commit`. The original (broken) version did:

```sh
stash_ref=$(git stash create)
git checkout -- .
trap 'git stash apply --quiet "$stash_ref" || true' EXIT
# pad markdown tables in staged files
git add <files>
```

The `trap` fired during the hook's exit — *before* `git commit` read the index to build the tree — and zeroed the index. The commit object got an empty tree.

## Current fix

The hook in this repo no longer does any stashing:

```sh
files=$(git diff --name-only --cached --diff-filter=ACM | grep -E '\.md$' || true)
[ -z "$files" ] && exit 0
echo "$files" | xargs ./tools/build/install/vote-dev/bin/vote-dev pad-tables --
echo "$files" | xargs git add
```

Trade-off documented at the top of the hook: if you have unstaged edits to a `.md` file that's also staged, the formatter will run against the working-tree file and `git add` will fold those unstaged edits into the commit. Stash them first if that matters.

## How to verify on your installed git

```sh
git --version
cd "$(mktemp -d)" && git init -q . && git config user.email t@t && git config user.name t \
  && git commit --allow-empty -qm base \
  && echo a > a && git add a && git commit -qm a \
  && echo b > a && git add a \
  && sref=$(git stash create) \
  && git stash apply --quiet "$sref" \
  && echo "INDEX after: '$(git ls-files --stage)'"
```

If the last line prints empty, the bug is present. If it prints the staged file entry, your git is fixed.

## Recommendation

Upgrade Git for Windows. `2.24.1` is over six years old; the stash internals have been substantially rewritten since, and modern versions of git (≥ 2.30) handle this sequence correctly in informal testing. Even after upgrade, prefer `stash push --keep-index` over `create`/`apply` for any future hook work.
