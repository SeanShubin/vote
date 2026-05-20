# Tideman vs Schulze: A Divergence Example

Tideman's Ranked Pairs and the Schulze method are siblings. Both
satisfy the Condorcet criterion, both produce a consistent ranking
even when the direct contests contain a cycle, and on the
overwhelming majority of real elections they pick the same winner.
This project runs Tideman — see [tideman-ranked-pairs.md](tideman-ranked-pairs.md)
for why — but it is worth having one concrete case on hand where the
two methods genuinely disagree.

A disagreement requires **at least four candidates**. With three
candidates and a cycle, both methods do the same thing: drop the
weakest defeat in the cycle. The example below is the smallest kind
of case where they part ways.

## The example

Four candidates — A, B, C, D — and six head-to-head results. Only
the *relative order* of the margins matters, so any consistent set
of ballots producing these winners and this strength order behaves
identically; the all-even margins here are chosen for readability.

| Matchup | Winner | Margin |
| ------- | ------ | ------ |
| A vs B  | A      | 2      |
| A vs C  | A      | 8      |
| A vs D  | **D**  | 4      |
| B vs C  | B      | 10     |
| B vs D  | B      | 12     |
| C vs D  | C      | 6      |

There is no Condorcet winner — every candidate loses at least one
matchup. The Smith set is all four candidates (cycles A → B → D → A
and A → C → D → A).

## Ranked Pairs → winner is A

Sort the defeats strongest first, then lock each one in unless it
would close a cycle with already-locked defeats:

| Step | Defeat | Strength | Action                                 |
| ---- | ------ | -------- | -------------------------------------- |
| 1    | B → D  | 12       | lock                                   |
| 2    | B → C  | 10       | lock                                   |
| 3    | A → C  | 8        | lock                                   |
| 4    | C → D  | 6        | lock                                   |
| 5    | D → A  | 4        | **skip** — A → C → D is already locked |
| 6    | A → B  | 2        | lock                                   |

Locked DAG: B → D, B → C, A → C, C → D, A → B. Nothing points to A,
so the topological order is **A > B > C > D**.

**Ranked Pairs winner: A.**

## Schulze → winner is B

Schulze ranks candidates by their strongest *beatpath* — the
strongest chain of defeats connecting one candidate to another,
where a chain is only as strong as its weakest link.

The decisive pair is A vs B:

- A's strongest path to B is the direct defeat, strength **2**.
- B's strongest path to A is B → D → A, strength **4** (the weakest
  link, D → A, is 4).

Since 4 > 2, Schulze ranks **B above A**. B also defeats C and D
directly and by large margins, so B sits on top.

**Schulze winner: B.**

## Why they diverge

Everything hinges on the D → A defeat (strength 4) and on the fact
that A's only claim over B is a direct win by a razor-thin margin
of 2.

- **Ranked Pairs treats the weakest defeat in a cycle as noise.** By
  the time the walk reaches D → A, the chain A → C → D is already
  locked. D → A would close a cycle, so it is discarded entirely.
  With D → A gone, nothing defeats A, and A wins.
- **Schulze keeps that same defeat alive as a link in a beatpath.**
  D → A becomes the final step of B → D → A. That path has strength
  4 — stronger than A's flimsy direct win over B — so Schulze
  considers B to beat A.

The philosophical split: when a candidate's only claim over a rival
is *weaker* than the chain the rival can route back through, Ranked
Pairs says "drop the chain — it was the weak link in a cycle,"
while Schulze says "the chain still counts." This project chose
Ranked Pairs because that decision — which specific contest got
dropped, and which stronger contests forced the drop — can be shown
to a voter directly, whereas a Schulze beatpath winding through
candidates a voter never ranked cannot.

## Why three candidates can never diverge

With three candidates in a cycle, there is exactly one cycle and
exactly one weakest defeat in it. Both methods remove that defeat
and rank the remaining two locked defeats. There is no room for the
two algorithms to disagree — a fourth candidate is what creates the
second cycle and the indirect beatpath that the methods treat
differently.
