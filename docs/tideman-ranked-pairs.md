# Tideman's Ranked Pairs

How this project counts ballots — and why it does it this way.

## What we run

For each election the server builds the pairwise preference matrix and
then runs **Tideman's Ranked Pairs** (TRS, Nicolaus Tideman, 1987) over
it. The result is a strict ranking with explicit ties where Tideman
can't separate two candidates.

Two policy choices come with this:

1. **Informed-voter rule.** A ballot only votes in a contest between A
   and B if it ranked both of them. Voters who omit one or both abstain
   from that contest entirely. No "unranked = last place" synthesis.
2. **Tideman's Ranked Pairs** as the Condorcet completion. When the
   pairwise contests contain a cycle (rock-paper-scissors at scale),
   Tideman locks contests into a DAG strongest-first and drops any
   contest that would close a cycle with already-locked contests.

Both choices are defensible on their own; they interact in a way the
last section of this doc spells out.

## Why we switched from Schulze

The project's earlier version used the Schulze method. Schulze and
Tideman are siblings — both satisfy the Condorcet criterion, both
produce a consistent ranking even when direct contests contain a
cycle — but they differ in how they explain themselves.

Schulze computes the **strongest path** between every pair of candidates
via a Floyd–Warshall closure: it asks not just "did A beat B directly?"
but "is there a transitive chain A → X → Y → … → B all of whose links
are strong?" The chain might wind through candidates the voter never
ranked, picking up indirect strength from ballots that abstained on
A-vs-B.

This produced a real incident in production. The Goodyng-vs-Cursed pair
was 2-0 in direct contests (the only two voters who ranked both put
Goodyng above Cursed). Schulze's closure built a transitive path
Cursed → Bloodruth → Goodyng with strength 2 through voters who didn't
rank Cursed at all, tying the strongest paths at 2-2. From Schulze's
perspective the pair was tied; Cursed then ranked above Goodyng in the
final placement because an unrelated candidate had a stronger path
against Goodyng than against Cursed. The 2-0 direct win was honored
nowhere visible in the result.

The reports (pairwise table, strongest-paths matrix) showed both facts,
but neither told the reader *why* the placement landed the way it did.
Tracing it required understanding the topological partition that
Schulze's place-assignment step performs — not something a voter is
expected to do.

Tideman's algorithm is different in two ways that matter for that
incident:

1. **Direct contests aren't laundered through indirect chains.** Each
   contest is locked into the final ranking as a *direct edge* or not
   at all. An A→B edge gets there because more voters preferred A to B,
   period — not because a chain through some other candidate
   strengthened the closure.
2. **The cycle resolution is a story.** When two contests can't both
   be honored, Tideman picks the stronger one (by winning votes) and
   reports the cycle path that forced the weaker one to be skipped.
   The report renders that path verbatim.

Trade-off to be aware of: Tideman doesn't *guarantee* that every direct
winner stays a winner. If the strongest contests in an election are
A→B (3 votes) and C→A (3 votes), and a weak C→D (2 votes) would close
a cycle with already-locked stronger contests, Tideman still drops the
weaker contest — even though it had no opposition. What Tideman *does*
guarantee is that the drop is visible: the report can point at the
specific stronger contests that prevented the lock-in.

## The algorithm in plain words

1. Compute the pairwise count for every (A, B) pair using the
   informed-voter rule.
2. For each unordered pair, emit *at most one* directed contest — the
   direction with the higher count. Ties produce no contest at all.
3. Sort the contests strongest first:
   - winning votes descending,
   - then losing votes ascending (less opposition is more decisive),
   - then alphabetically by winner and loser, for deterministic
     tiebreaks across contests that match on the numbers.
4. Walk the sorted list. For each contest, lock it into the DAG
   *unless* doing so would close a cycle with already-locked edges.
   A skipped contest records the locked path that closes the cycle, so
   the report can quote it.
5. Place candidates by topological layering of the locked DAG. Each
   layer is the candidates that have no incoming locked edge from any
   still-remaining candidate; everyone in a layer shares a place.

The Condorcet criterion holds. If a candidate beats every other
candidate directly, all their outgoing contests lock in (they never
have an incoming edge to close a cycle into), and topology puts them
alone at place 1.

### The alphabetical tiebreak can affect the ranking

When two contests have the same winning votes *and* the same losing
votes, step 3 falls through to a sort by winner name then loser name.
That tiebreaker is normally invisible (it just decides display order
within a "bucket" of equally-strong contests), but in one case it can
change the outcome: when several equally-strong contests form a cycle.

Pathological example: three candidates A, B, C with A→B, B→C, and C→A
all at strength 3-3 (a clean rock-paper-scissors at equal votes).
Whichever two of those three lock first determine the ranking — the
third creates a cycle and gets skipped. Under our alphabetical
tiebreak: A→B locks (winner A), then B→C locks (winner B), then C→A
is skipped because A→B→C already exists. Final: A > B > C.

Tideman's original 1987 paper resolves this with a random ballot.
That removes the alphabetical bias at the cost of determinism — the
result of an election with a perfect numeric tie among cycle edges
could come out differently on different runs. We chose determinism;
alphabetically-earlier candidates win perfect ties as a side effect.
In practice, the perfect-numeric-tie case is rare enough that this
trade-off is mostly theoretical, but it's not nothing — it's worth
being explicit about.

## A worked example

Three candidates — Apple, Banana, Cherry — and five voters. Two voters
ranked all three; three only ranked two of them.

| Voter | Ballot                  |
| ----- | ----------------------- |
| v1    | Apple > Banana > Cherry |
| v2    | Apple > Banana > Cherry |
| v3    | Apple > Cherry          |
| v4    | Cherry > Apple          |
| v5    | Cherry > Apple          |

Pairwise counts (informed-voter rule — abstainers don't count):

- **Apple vs Banana**: v1, v2 only (v3, v4, v5 omit Banana). Apple
  wins 2-0.
- **Apple vs Cherry**: v1 (A>C), v2 (A>C), v3 (A>C), v4 (C>A), v5 (C>A).
  Apple 3, Cherry 2 — Apple wins 3-2.
- **Banana vs Cherry**: v1 (B>C), v2 (B>C). Banana wins 2-0.

No cycle. Three contests, sorted strongest first:

| Step | Contest         | Winning votes | Losing votes |
| ---- | --------------- | ------------- | ------------ |
| 1    | Apple → Cherry  | 3             | 2            |
| 2    | Apple → Banana  | 2             | 0            |
| 3    | Banana → Cherry | 2             | 0            |

(Step 2 beats step 3 on the tiebreak: same winning votes, same losing
votes, Apple < Banana alphabetically.)

Lock-in walk:

- Step 1: lock Apple → Cherry. DAG: {Apple → Cherry}.
- Step 2: lock Apple → Banana. DAG: {Apple → Cherry, Apple → Banana}.
  No path Banana → Apple exists, so no cycle.
- Step 3: lock Banana → Cherry. DAG: {Apple → Cherry, Apple → Banana,
  Banana → Cherry}. No path Cherry → Banana exists.

Topological layering:

- Layer 1: Apple (no incoming edges). Place 1.
- After removing Apple: Banana has no incoming edges. Place 2.
- After removing Banana: Cherry. Place 3.

Final: **Apple > Banana > Cherry**.

### What changes when there's a cycle

Replace v3 with a fourth all-three-candidate ballot to introduce a
cycle:

| Voter | Ballot                  |
| ----- | ----------------------- |
| v1    | Apple > Banana > Cherry |
| v2    | Apple > Banana > Cherry |
| v3'   | Banana > Cherry > Apple |
| v4    | Cherry > Apple          |
| v5    | Cherry > Apple          |

Pairwise:

- **Apple vs Banana**: v1 (A>B), v2 (A>B), v3' (B>A). Apple 2, Banana
  1 — Apple wins 2-1.
- **Apple vs Cherry**: v1 (A>C), v2 (A>C), v3' (C>A), v4 (C>A), v5 (C>A).
  Apple 2, Cherry 3 — Cherry wins 3-2.
- **Banana vs Cherry**: v1 (B>C), v2 (B>C), v3' (B>C). Banana wins 3-0.

Cycle: Apple > Banana > Cherry > Apple.

Sorted contests:

| Step | Contest         | Winning | Losing |
| ---- | --------------- | ------- | ------ |
| 1    | Banana → Cherry | 3       | 0      |
| 2    | Cherry → Apple  | 3       | 2      |
| 3    | Apple → Banana  | 2       | 1      |

Lock-in walk:

- Step 1: Banana → Cherry locks. DAG: {Banana → Cherry}.
- Step 2: Cherry → Apple locks. (No path Apple → Cherry exists; no
  cycle.) DAG: {Banana → Cherry, Cherry → Apple}.
- Step 3: Apple → Banana would close a cycle: Banana → Cherry → Apple
  already exists, so adding Apple → Banana would create
  Apple → Banana → Cherry → Apple. **Skipped**. The report records the
  cycle path `[Banana, Cherry, Apple]`.

Locked DAG: {Banana → Cherry, Cherry → Apple}.

Topology: Banana → Cherry → Apple. Final: **Banana > Cherry > Apple**.

The Apple → Banana contest, which existed and had 2 winning votes, did
not survive. The report shows it as step 3, status Skipped, with the
cycle path that blocked it.

## The three reports

The Results tab links to three detail pages. Each grounds a different
question.

1. **Preferences** — *Who voted which way?* For the selected pair, the
   raw pairwise counts plus the named voter lists behind each total
   (and an explicit list of voters who abstained on the contest by
   omitting at least one candidate). This is the auditable "what
   actually went into the algorithm" view.

2. **Decision** — *What did Tideman do with the contest between these
   two candidates?* Same per-pair starting point as Preferences, plus
   the Tideman verdict: the contest was locked in, was skipped because
   of a cycle (with the locked path that closed it), or never existed
   at all because the pair was tied in pairwise count. This is the
   answer to "we agreed about A and B; why didn't the algorithm?"

3. **Process** — *What did Tideman do, in order, for the whole
   election?* Every contest as a row in lock-in order, each marked
   Locked or Skipped, each skipped row carrying the cycle path that
   forced it. This is the global narrative — the chronological story
   of how the final ranking was assembled.

Typical flow: a reader curious about why two candidates landed where
they did opens Preferences for the raw vote, then Decision for the
algorithm's per-pair verdict, then Process if they want to see where
in the global order things were decided.

## The informed-voter rule and absence shielding

A consequence worth knowing about: candidates who appear on few
ballots are harder to defeat than candidates who appear on many.

Mechanically: every contest is capped at the number of voters who
ranked *both* candidates. If only two voters ranked candidate C, then
every contest C-vs-X has at most strength 2 in either direction — no
matter how many voters rated X. A heavily-rated candidate's losses to
C are capped, so the strong contests that would have decisively
defeated C never appear in the lock-in walk.

This isn't a Tideman behavior — it's an abstention-rule behavior. The
alternative ("omitted = last place") would let a voter who ranked two
candidates effectively cast 17 anti-votes against the other 17
candidates in the election, which is its own pathology. We accept the
absence-shielding side effect because the informed-voter rule has the
better failure mode: it never puts words in a voter's mouth.

The Tideman switch makes this trade-off **visible** rather than fixing
it. Under Schulze the absence shield led to surprising final
placements that the reports couldn't explain. Under Tideman, the
shield still exists, but the Decision page can show "this contest
existed but was skipped because of [these specific stronger contests],
which themselves existed only because [these specific voters] could be
counted in them." A reader can see the trade-off rather than be
mystified by it.
