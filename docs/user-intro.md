It's a small online voting system. People sign up, an admin promotes them to a role that can create elections, and anyone signed in can vote in any election that exists. An election has a name, a list of
candidates, and optionally some "tier" labels like Pass / Fail or Gold / Silver / Bronze. Voters cast a ranked ballot — they put the candidates in order of preference rather than picking just one. They can
change or delete their ballot later. The system then tallies the results and shows the standings.

There are some supporting features around it: log in, password reset, profile editing, an admin view for managing users, and a debug view for whoever is auditing the system. But the core is "create an election,
rank the candidates, see who won."

How "comparing in pairs" works — for a non-technical reader

In a regular election, you pick one favorite. Here, you put the candidates in order — like "Kotlin first, Rust second, Go third." That richer information lets the system run a much fairer count.

Here's the idea: imagine the candidates as a round-robin tournament. Take any two of them — say, Kotlin vs. Rust — and ask every voter who ranked both, "On your ballot, did you rank Kotlin higher than Rust, or
Rust higher than Kotlin?" Whichever name got the higher rank from more of those voters wins that matchup. Repeat for every possible pair — Kotlin vs. Go, Rust vs. Go, and so on. Now you have a complete record of
who beats whom, one-on-one.

The winner is the candidate who beats everyone else head-to-head. Second place beats everyone except the winner. And so on. If two candidates beat the same opponents and tie against each other, they share a
place.

A couple of subtleties worth knowing:

- If you leave someone off your ballot, you simply sit out the matchups involving them. It doesn't count as a vote against them — it's an abstention. So you only express preferences you actually have.
- Sometimes no candidate beats every other one directly. Maybe A beats B, B beats C, but C beats A — like rock-paper-scissors. The system resolves this with **Tideman's Ranked Pairs**: it considers every matchup
  in order of strength (most votes for the winner first, with less opposition breaking ties) and locks each one into the final ranking unless doing so would contradict a stronger matchup already locked in. The
  weakest matchup in a cycle is the one that gets dropped — and the system shows you exactly which stronger matchups forced the drop, so the resolution is auditable rather than mysterious.
- Tiers (the optional Pass / Fail style labels) act like extra "virtual candidates" in the same tournament. A real candidate's tier in the result is the hardest one they beat in a head-to-head — so you can see
  not just the ranking but the caliber.

The upshot: nobody is picked just because they had a passionate minority. The winner has to hold up against every other candidate, one matchup at a time — and when matchups conflict with each other, the
stronger matchup wins, in full view.
