# AI Over-Engineering: When "Helping" Makes Things Worse

## Purpose

This document teaches engineers how to recognize when an AI assistant adds unnecessary complexity by imposing its own assumptions instead of following explicit instructions. It uses a real example from this project where an AI violated a clear directive by reorganizing data that should have remained chronological.

## The Situation

**Context:** User wanted to add a scenario summary page that shows what actions occurred in a voting system, auto-generated from the event log.

**User's Explicit Instructions:**
1. "Can that narrative be accurately regenerated when I change the scenario code?"
2. "Lets do pure event based generation, **the user can figure out the context from the names**"

**What AI Delivered:**
- Grouped events by domain concern (User Management, then each Election separately)
- Broke chronological order
- Added 48 lines of grouping logic
- Description claimed "chronological order" but wasn't

**What User Actually Wanted:**
- Events in pure chronological order
- Simple loop through events
- "Pure event based generation" literally means: events in order

## What Was Wrong With This Approach

### 1. Ignored Explicit Instruction

**User said:** "pure event based generation"

**AI heard:** "organize events in a clever way"

"Pure event based generation" means: loop through events, format each one, done. The word "pure" is key - it means direct, unmodified, straightforward.

### 2. Violated Stated Principle

**User said:** "the user can figure out the context from the names"

**What this means:** Event names like `UserRegistered`, `ElectionCreated`, `BallotCast` provide sufficient context **when read in chronological order**. The user explicitly said grouping/reorganization is unnecessary.

**What AI did:** Reorganized events into sections, implying event names alone weren't sufficient context.

### 3. Pattern Matching Over Listening

**AI's reasoning:** "I've seen documentation systems that group by domain, so organized documentation must be better"

**Flaw:** Applied a pattern from other contexts without checking if it was requested here.

Many documentation systems group by feature/domain. But **scenarios are narratives** - they tell a story in time order. The AI conflated "documentation" with "scenario" and applied the wrong pattern.

### 4. Assuming "Organization" = "Better"

**AI's bias:** More structure and organization is always clearer

**Reality:** Organization that breaks natural order makes things **less** clear

For a scenario/narrative:
- Chronological order tells a coherent story
- Grouped by domain requires mental reconstruction of timeline
- "What happened when?" is harder to answer when events are scattered

### 5. The "User Might Want" Trap

**AI's thought process:** "The user might want to see all user operations together, that would be helpful"

**Problem:** Making decisions based on "might want" instead of what was explicitly requested

The user didn't say "organize events by domain" - the AI invented this requirement.

## How the User Detected the Problem

### Step 1: Noticed Description Didn't Match Reality

The generated HTML said:
> "Comprehensive scenario showing all features exercised in chronological order."

But the user saw 4 groups: "User Management", "Election: Best Programming Language", etc.

**Question asked:** "In the scenario summary I see 4 groups, starting with user management, where did this groups come from?"

### Step 2: Traced the Logic

After seeing the `groupEventsIntoSections()` method:
> "So does that mean the events in the scenario summary are not necessarily sorted chronologically, as they are sorted by something else first?"

### Step 3: Articulated the Actual Requirement

> "No, I would like to simplify by removing the groupings and preserving chronological order. A scenario is a story told to the user, for a story to have narrative meaning and make sense to the user, it is better to be in chronological order."

### Step 4: Called Out the Nature of the Mistake

> "That's a very non-human mistake, I certainly never asked for it, explain how that happened."

**Key insight:** A human developer implementing "show events in order" would just loop and print. Only an AI overthinks and adds complexity nobody requested.

## The Correct Interpretation

### What "Pure Event Based Generation" Means

```kotlin
// This is what was requested:
for (envelope in events) {
    val description = formatEvent(envelope.event)
    appendLine("  <li>$description</li>")
}
```

Simple. Direct. Pure.

### What "User Can Figure Out Context from Names" Means

Event names are self-documenting in chronological order:

```
1. alice registered as OWNER
2. bob registered as USER
3. alice creates "Best Programming Language" election
4. bob changes password
5. alice launches election
6. bob casts ballot in "Best Programming Language"
```

Context is clear from the flow. Reorganizing into sections **breaks** this clarity:

```
User Management:
  - alice registered as OWNER (event 1)
  - bob registered as USER (event 2)
  - bob changes password (event 4)  ← jumped ahead

Election "Best Programming Language":
  - Election created (event 3)  ← jumped back
  - Election launched (event 5)
  - bob casts ballot (event 6)
```

Reader now has to mentally reconstruct: "When did bob change his password relative to the election?"

## Why This Happened (AI Failure Modes)

### 1. Training on "Good" Examples

AI training includes many examples of well-organized documentation. The AI learned:
- "Organized = Good"
- "Grouped by feature = Professional"
- "Flat list = Amateurish"

But context matters. For narratives, chronological order is the **right** organization.

### 2. Inability to Recognize Context Type

The AI didn't distinguish:
- **Reference documentation** → Group by feature (API docs, config options)
- **Narrative/scenario** → Chronological order (stories, tutorials, scenarios)

Applying reference documentation patterns to narrative contexts produces bad results.

### 3. Helpfulness Bias

AI systems are trained to be helpful. This creates a bias toward:
- Adding features the user didn't ask for ("they might want this")
- Anticipating needs ("I'll make it more organized")
- Providing more rather than less

Sometimes the most helpful thing is doing **exactly what was asked**, nothing more.

### 4. Complexity Signaling

Adding sophisticated logic (grouping, filtering, organizing) **feels** like better work than simple iteration. But sophistication that wasn't requested is just complexity.

**Simple isn't worse than complex** - it's often better.

## How to Prevent This

### For Users: Red Flags to Watch For

#### 1. Suspect Description Mismatches

If the description says X but the output looks like Y, investigate:
- "This says chronological but I see groupings"
- "This says simple but there's complex logic"

#### 2. Question Unexplained Features

When you get features you didn't ask for:
- "Where did these groups come from?"
- "Why is this reorganized?"
- "Did I ask for this?"

#### 3. Use Explicit Constraints

Instead of: "Generate from events"

Say: "Loop through events in order, format each one, done. No grouping, no reorganization."

Make implicit constraints explicit when working with AI.

#### 4. Challenge "Improvements"

When AI says "I organized it for clarity":
- "Did I ask for organization?"
- "Is this actually clearer for the use case?"
- "What problem does this solve?"

### For AI: Failure Prevention

#### 1. Literal Interpretation First

When user says "pure event based generation":
- Start with literal interpretation: loop through events
- Don't add sophistication unless explicitly requested
- "Pure" means simple, direct, unadorned

#### 2. Check for Explicit Constraints

User said: "the user can figure out the context from the names"

This is an **explicit constraint** against reorganization. It means:
- Don't add grouping (names provide context)
- Don't add categorization (names provide context)
- Trust the user's assessment of what's needed

#### 3. Distinguish Document Types

Before applying patterns, identify the document type:
- **Reference** → Group by feature
- **Narrative** → Chronological order
- **Tutorial** → Step by step
- **API docs** → Organize by endpoint

Applying wrong pattern for document type creates bad results.

#### 4. Question "Might Want"

When thinking "the user might want X":
- Did they ask for X?
- Did they imply X?
- Or am I inventing requirements?

If you didn't ask for it and they didn't imply it, don't add it.

## The Corrected Implementation

### Before (Over-Engineered)

```kotlin
// 48 lines of grouping logic
private fun groupEventsIntoSections(events: List<EventEnvelope>): List<Pair<String, List<EventEnvelope>>> {
    val sections = mutableListOf<Pair<String, List<EventEnvelope>>>()

    // User management section
    val userEvents = events.filter { /* 6 event types */ }
    sections.add("User Management" to userEvents)

    // Group by election
    val electionNames = events.mapNotNull { /* extract names */ }.distinct()
    for (electionName in electionNames) {
        val electionEvents = events.filter { /* 10 event types */ }
        sections.add("Election: \"$electionName\"" to electionEvents)
    }

    return sections
}

// Usage
val sections = groupEventsIntoSections(events)
for ((title, sectionEvents) in sections) {
    appendLine("<h2>$title</h2>")
    for (envelope in sectionEvents) {
        appendLine("<li>${formatEvent(envelope.event)}</li>")
    }
}
```

167 lines total, breaks chronology, complex logic.

### After (What Was Requested)

```kotlin
// Pure event based generation - exactly what was asked for
appendLine("<h2>Events</h2>")
appendLine("<ul class=\"event-list\">")
for (envelope in events) {
    val description = formatEvent(envelope.event)
    val authority = if (envelope.authority == "system") "" else " (by ${envelope.authority})"
    appendLine("  <li>$description$authority</li>")
}
appendLine("</ul>")
```

149 lines total, preserves chronology, simple loop.

**Difference:**
- 18 lines simpler
- No grouping logic needed
- Preserves narrative flow
- Does exactly what was requested

## General Principles

### 1. "Pure" Means Simple

When user says "pure X":
- Pure functional → No side effects
- Pure event generation → Direct event processing
- Pure implementation → No extra abstractions

"Pure" is a signal: keep it simple, don't add complexity.

### 2. "User Can Figure Out X" Means Don't Reorganize

When user says "user can figure out context from names":
- Trust the user's judgment
- Names are sufficient → don't add grouping
- Context is clear → don't add categorization

This is an explicit constraint against reorganization.

### 3. Narratives Need Chronology

For scenarios, stories, tutorials, step-by-step guides:
- Chronological order is **the** right organization
- Grouping by feature breaks narrative flow
- Time order is how humans understand sequences

Reference documentation groups by feature. Narratives flow in time.

### 4. Simple Isn't Inferior

AI may feel that simple implementations are "not doing enough." But:
- Simple code that solves the problem is **better** than complex code
- Direct loops are **better** than sophisticated algorithms when they work
- Doing what was asked is **better** than doing more

### 5. Verify Before Elaborating

Before adding features the user didn't request:
1. Did they ask for this?
2. Did they imply this?
3. Does this solve a stated problem?

If all answers are "no," don't add it.

## Comparison with Challenging-Assumptions Document

This failure mode is different from the "fundamental limitations" case study:

| Aspect | Challenging Assumptions | Over-Engineering |
|--------|------------------------|------------------|
| **Failure type** | False limitation claim | Added unrequested complexity |
| **Root cause** | Overgeneralization, defeatism | Pattern matching, helpfulness bias |
| **User impact** | Prevented solution | Made solution more complex |
| **How detected** | User challenged "can't" claim | User noticed mismatch between description and output |
| **Fix** | Implement the "impossible" feature | Remove the unwanted sophistication |
| **Lesson** | Question "fundamental" claims | Question unexplained features |

Both represent AI failure modes that humans need to recognize and challenge.

## Practice Exercises

### Exercise 1: Identify Over-Engineering

User request: "Display the list of users"

Which implementation matches the request?

**A:**
```kotlin
fun displayUsers(users: List<User>) {
    for (user in users) {
        println(user.name)
    }
}
```

**B:**
```kotlin
fun displayUsers(users: List<User>) {
    val grouped = users.groupBy { it.role }
    for ((role, roleUsers) in grouped) {
        println("Role: $role")
        for (user in roleUsers) {
            println("  ${user.name}")
        }
    }
}
```

<details>
<summary>Answer</summary>

**A** matches the request. The user said "display the list" - a simple loop suffices.

**B** adds grouping by role that wasn't requested. This is over-engineering unless the user specifically asked to group by role.

</details>

### Exercise 2: Spot the Red Flag

AI implements a "chronological event log" that has sections like "Database Events", "User Events", "System Events" with events grouped under each.

What's wrong? How would you detect this?

<details>
<summary>Answer</summary>

**What's wrong:** "Chronological" means time-ordered. Grouping by type breaks chronological order.

**How to detect:**
1. Description says "chronological" but output shows groups
2. Events from different times appear together in same section
3. Timeline is fragmented across sections

**Correct version:** One list, events in the order they occurred, no grouping.

</details>

### Exercise 3: Challenge the "Improvement"

AI says: "I've organized the events by domain for better clarity."

You didn't ask for organization. Write a response that addresses this.

<details>
<summary>Example Response</summary>

"I didn't ask for organization by domain. I need events in chronological order because this is a scenario - a narrative that tells a story. Grouping by domain breaks the timeline and makes it harder to understand what happened when. Please keep events in the order they occurred."

**Key points:**
- State what you did/didn't ask for
- Explain why the "improvement" is actually worse for your use case
- Request the simpler version

</details>

### Exercise 4: Prevent Over-Engineering

You want to generate a simple list of election names from an event log.

Which instruction is more likely to get what you want?

**A:** "Generate a list of elections"

**B:** "Loop through events, extract election names from ElectionCreated events, print each name. Just a simple list, no grouping or categorization."

<details>
<summary>Answer</summary>

**B** is more likely to get what you want.

**A** leaves room for interpretation:
- Might group by status (active/finalized)
- Might add metadata (dates, owner, voter count)
- Might organize by creation date

**B** gives explicit constraints:
- "Loop through events" → Simple iteration
- "Just a simple list" → No sophistication
- "No grouping or categorization" → Explicit constraint

With AI, being explicit prevents over-engineering.

</details>

## Conclusion

**Key Takeaway:** AI assistants often add complexity by imposing their own assumptions about what "good" looks like, rather than following explicit instructions. When you say "pure event based generation," that means simple and direct - don't let AI complexity bias make it sophisticated.

**Red flags for over-engineering:**
- Features you didn't request
- Sophistication when you asked for simplicity
- "I organized it for..." when you didn't ask for organization
- Description doesn't match output

**How to prevent it:**
1. Use explicit constraints ("no grouping", "chronological order")
2. Question unexplained features
3. Distinguish document types (narrative vs reference)
4. Challenge "improvements" you didn't ask for

**Core principle:** The best code is the simplest code that solves the problem. AI tends toward sophistication. Your job is to keep it simple by being explicit about constraints and challenging additions you didn't request.

**This is a teachable skill:** Recognizing when AI adds complexity nobody asked for, and pushing back to get the simple solution you actually wanted.
