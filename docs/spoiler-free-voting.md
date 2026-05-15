# Spoiler-Free Voting Method

## Goal

- Given many individuals with preferences between candidates, each
  expressed as an ordered ranking,
- return a single ordered ranking of candidates that reflects the
  combined preferences of the voters.
- A voter expressing their true preference should not have to worry
  about their vote working against them.
- Many voting systems — first-past-the-post and instant-runoff voting
  among them — incentivize voting tactically for the lesser of two
  evils rather than expressing true preference. The system used here
  is designed to remove that pressure.

## Mechanics

- Instead of checking which candidate got the most first-place votes,
  as in first-past-the-post,
- and instead of eliminating the candidate with the fewest first-place
  votes round by round, as in instant-runoff voting,
- every candidate is compared individually to every other candidate.
- If one candidate wins every head-to-head matchup, that candidate
  wins (this is known as the **Condorcet Criterion**).

## Cycles

- Sometimes there's no candidate who beats everyone else
  head-to-head — instead you get a cycle, like rock-paper-scissors:
  rock beats scissors, scissors beats paper, paper beats rock.
- To resolve cycles we use a method called **Ranked Pairs** (Nicolaus
  Tideman, 1987). The idea is to lock matchups into the final ranking
  one at a time, strongest first. If a matchup would contradict
  matchups already locked in — by closing a cycle — it's skipped.
- "Strongest first" means the matchup with the most votes for the
  winning candidate, with less opposition breaking ties between
  matchups that have the same winning-vote count.
- When every matchup in a cycle has the same strength as every
  other matchup in the same cycle, the algorithm refuses to pick
  arbitrarily and treats those candidates as tied.

## Examples

### Why pairwise methods are superior to first-past-the-post

Let's say you have 3 candidates: "minor-improvements", "status-quo",
and "radical-changes".

```
30% of the population prefers minor-improvements, then status-quo, then radical-changes
30% of the population prefers status-quo, then minor-improvements, then radical-changes
40% of the population prefers radical-changes, then minor-improvements, then status-quo
```

If you only count first-place votes, radical-changes wins, even
though this does not accurately reflect voter preference. To see why,
consider:

    70% of voters would rather have minor-improvements than status-quo
    60% of voters would rather have minor-improvements than radical-changes
    60% of voters would rather have status-quo than radical-changes

When the candidates are compared pairwise, the accurate preference is
minor-improvements, then status-quo, then radical-changes. But when
only the voter's top candidate is considered, so much information is
thrown away that the least-preferred candidate wins.

A pairwise method ranks them minor-improvements > status-quo >
radical-changes — consistent with the actual voter preference.

### How pairwise methods reduce tactical voting

Consider this dilemma:

    3 voters prefer minor-improvements, then status-quo, then radical-changes
    4 voters prefer status-quo, then minor-improvements, then radical-changes
    2 voters prefer radical-changes, then minor-improvements, then status-quo

If we're counting first-place votes (or running instant-runoff),
although the 2 voters prefer radical-changes, they don't dare vote
that way because doing so would throw the election to status-quo
rather than minor-improvements. It is in the best interest of those
2 voters to **not** express their preference accurately.

This misrepresents the number of voters who actually preferred
radical-changes — that information is lost.

A pairwise method compares the candidates like so:

    minor-improvements defeats status-quo 5 to 4
    minor-improvements defeats radical-changes 7 to 2
    status-quo defeats radical-changes 7 to 2

So the ranking becomes minor-improvements > status-quo >
radical-changes. The 2 voters could accurately express their
preference without sabotaging their own interests.

### Why pairwise methods are superior to instant-runoff voting

Instant-runoff voting works like this:

> Ballots are initially counted for each elector's top choice, losing
> candidates are eliminated, and ballots for losing candidates are
> redistributed until one candidate is the top remaining choice of a
> majority of the voters.

The problem is that since only the top choices are counted each
round, candidates closer to the consensus preference can be the first
ones eliminated. To illustrate, consider 4 candidates where every
voter has a different top choice but agrees on a strong second:

    4 voters prefer niche, then satisfactory, then bought, then cult
    3 voters prefer bought, then satisfactory, then niche, then cult
    3 voters prefer cult, then satisfactory, then niche, then bought

In both instant-runoff and first-past-the-post, "niche" wins — even
though 60% of voters preferred "satisfactory" to "niche".
"Satisfactory" — the one every single voter preferred over someone
else's top candidate — was the first to be eliminated.

### How circular ambiguities are resolved using Ranked Pairs

Let's say you have 3 candidates: "rock", "paper", and "scissors".
Initially there are 9 votes:

    3 voters rank candidates: rock, scissors, paper
    3 voters rank candidates: paper, rock, scissors
    3 voters rank candidates: scissors, paper, rock

This is a 3-way tie. Now what should happen when a 10th voter casts:

    1 voter ranks candidates: rock, scissors, paper

The intuition is that there's now enough information to break the
tie, with the outcome aligned with the last ballot cast. Computing
this result is less obvious. The pairwise matchups are:

    rock defeats scissors 7 to 3
    paper defeats rock 6 to 4
    scissors defeats paper 7 to 3

This looks circular: each candidate loses to one and beats another.
**Ranked Pairs** resolves it by walking the matchups strongest first
and locking each one into the ranking unless doing so would create a
cycle with matchups already locked in.

Sorted strongest first (more winning votes first, less opposition
breaking ties):

    Step 1: rock defeats scissors 7 to 3   ← strongest
    Step 2: scissors defeats paper 7 to 3
    Step 3: paper defeats rock     6 to 4   ← weakest

Walking through:

- **Step 1** locks `rock → scissors`. No conflict — nothing else is
  locked yet.
- **Step 2** locks `scissors → paper`. Together with step 1, the
  ranking so far is `rock > scissors > paper`. No cycle.
- **Step 3** would add `paper → rock`, but the locked-in path is
  already `rock → scissors → paper`. Adding `paper → rock` on top
  would close that path into a cycle (`rock → scissors → paper →
  rock`), so it's **skipped**.

Final ranking:

    1st rock
    2nd scissors
    3rd paper

This matches the intuition from the 10th ballot.

The strongest matchups are honored as long as they don't contradict
each other. When they do, the weakest one is the one that gets
dropped — and the report on each pairwise contest shows exactly which
stronger matchups forced the drop.

### When the cycle members are perfectly tied in strength

Sometimes the matchups inside a cycle all have *identical* numbers.
For example, three candidates A, B, C where:

    A defeats B 4 to 2
    B defeats C 4 to 2
    C defeats A 4 to 2

All three matchups have the same winning votes and the same losing
votes. Picking which two to lock and which one to drop would require
an arbitrary tiebreaker — like alphabetical order — that doesn't
reflect anything voters actually expressed.

In this case the system refuses to pick. None of the three matchups
lock, and A, B, and C tie at the same place in the final ranking.
The honest answer to "which of these is ahead?" is "the votes don't
say."

## Voting Criteria

> But what about *some-criterion-here*? Doesn't that make this voting
> system invalid?

This is a practical solution to a real problem: people feeling they
can't express their true preference because the mechanics of the
voting system will work against them if they do. There are infinitely
many criteria one could imagine such that it's logically impossible
for any voting method to satisfy all of them — see Arrow's impossibility
theorem and friends.

A more useful question is: can you think of a specific case where
expressing your preferences honestly causes the outcome to be further
from what you want? If not, then the system is doing its job.
