# AI Failure Modes: A Comprehensive Index

## Purpose

This document catalogs common failure modes when working with AI assistants, organized by type and severity. Each failure mode links to detailed case studies from real situations in this project.

## How to Use This Document

- **Engineers learning to work with AI:** Start here to understand common pitfalls
- **During code review:** Reference when AI-generated code seems off
- **When something feels wrong:** Look up symptoms to identify the failure mode
- **Teaching others:** Use as curriculum for AI interaction training

## Failure Mode Categories

### 1. False Limitation Claims
When AI incorrectly states something is impossible or "fundamental" when it's actually solvable.

### 2. Unwanted Complexity
When AI adds sophistication, features, or organization that nobody requested.

### 3. Context Misunderstanding
When AI applies patterns from the wrong context or document type.

### 4. Assumption Imposition
When AI makes decisions based on "might want" rather than explicit requests.

## Detailed Failure Modes

### FM-001: False Fundamental Limitations

**Category:** False Limitation Claims

**Symptom:** AI claims something "can't" be done due to "fundamental limitations" when it's actually a solvable design problem.

**Real Example:** "Backend logs cannot capture client-side errors that prevent communication. This is a fundamental limitation."

**Reality:** Only true if network is completely down (rare). For application errors, client can report to server (common case).

**Red Flags:**
- Claims of "fundamental limitation" without physics/math basis
- Defeatist language: "can't", "impossible", "just how it is"
- Conflates rare edge cases with common scenarios
- Discourages exploring solutions

**How to Challenge:**
1. Ask: "Is this a physics/math constraint or a design limitation?"
2. Thought experiment: "What would need to be true for X to work?"
3. Separate: Infrastructure failure (rare) vs. application error (common)
4. Request: "Let's solve the common case"

**Case Study:** [Challenging Assumptions](challenging-assumptions.md)

**Related:** FM-002 (Overgeneralization)

---

### FM-002: Overgeneralization from Edge Cases

**Category:** False Limitation Claims

**Symptom:** AI treats rare edge cases as if they invalidate solutions for common cases.

**Real Example:** "Server-side logging can't capture client errors" - true only when network is completely down (<5% of cases), but AI applied it to all cases (100%).

**Red Flags:**
- Solution dismissed because it doesn't work in 100% of cases
- Edge case treated as primary concern
- No distinction between common and rare scenarios
- "It won't work if..." followed by unlikely scenario

**How to Challenge:**
1. Ask: "How common is that scenario?"
2. Request: "Let's solve the 95% case, document the 5% limitation"
3. Separate: Common cases that can be solved vs. rare cases that can't
4. Push: Don't let rare cases block common solutions

**Case Study:** [Challenging Assumptions](challenging-assumptions.md) - Section "Separate Rare from Common"

**Related:** FM-001 (False Limitations), FM-008 (Missing Context)

---

### FM-003: Unrequested Complexity

**Category:** Unwanted Complexity

**Symptom:** AI adds sophisticated logic, grouping, or organization when simple, direct implementation was requested.

**Real Example:** User: "pure event based generation" → AI: Added 48 lines of grouping logic, broke chronological order

**Red Flags:**
- Features you didn't request
- Complex logic for simple requirements
- "I organized it for clarity" when you didn't ask for organization
- Sophisticated when you asked for simple

**How to Challenge:**
1. Ask: "Did I request this feature/organization?"
2. Point out: "I said 'pure' which means simple and direct"
3. Request: "Remove the sophistication, just do X"
4. Clarify: Make implicit constraints explicit

**Case Study:** [AI Over-Engineering](ai-overengineering.md)

**Related:** FM-004 (Pattern Misapplication), FM-006 (Helpfulness Bias)

---

### FM-004: Wrong Pattern for Document Type

**Category:** Context Misunderstanding

**Symptom:** AI applies organizational patterns from one document type to another where they don't fit.

**Real Example:** Applied "reference documentation" pattern (group by feature) to a narrative scenario (should be chronological).

**Document Types vs. Organization:**
- **Narrative/Scenario** → Chronological order (tells a story)
- **Reference docs** → Group by feature/topic (API docs)
- **Tutorial** → Step by step (sequential learning)
- **Troubleshooting** → Group by symptom (diagnostic)

**Red Flags:**
- Chronological content grouped by category
- Tutorial steps reorganized by topic
- Scenario events sorted by type instead of time
- Pattern doesn't match document purpose

**How to Challenge:**
1. Identify: "What type of document is this?"
2. State: "Scenarios need chronological order, not grouping"
3. Explain: Why the pattern doesn't fit the use case
4. Request: Pattern appropriate for document type

**Case Study:** [AI Over-Engineering](ai-overengineering.md) - Section "Inability to Recognize Context Type"

**Related:** FM-003 (Unrequested Complexity), FM-005 (Literal vs. Intended)

---

### FM-005: Ignoring Literal Meaning

**Category:** Assumption Imposition

**Symptom:** AI interprets request figuratively or elaborates when literal interpretation was intended.

**Real Example:** "pure event based generation" literally means: loop through events, format each one. AI added grouping, filtering, reorganization.

**Key Phrases That Mean "Keep It Simple":**
- **"Pure"** → Direct, unadorned, no additions
- **"Just"** → Only this, nothing more
- **"Simple"** → Basic, straightforward
- **"Direct"** → No indirection or transformation

**Red Flags:**
- AI adds sophistication when you said "simple"
- Elaborates when you said "just do X"
- Transforms when you said "pure"
- Interprets "display list" as "organize and present"

**How to Challenge:**
1. Quote back: "I said 'pure' which means..."
2. Be explicit: "Loop through, format, done. No grouping."
3. State what NOT to do: "No reorganization, no categorization"
4. Literal example: Show exactly what you want

**Case Study:** [AI Over-Engineering](ai-overengineering.md) - Section "What 'Pure Event Based Generation' Means"

**Related:** FM-003 (Unrequested Complexity), FM-006 (Helpfulness Bias)

---

### FM-006: Helpfulness Bias

**Category:** Assumption Imposition

**Symptom:** AI adds features based on "user might want" rather than what was explicitly requested.

**Real Example:** User: "Show events" → AI: "They might want to see user events grouped together, that would be helpful"

**Manifestations:**
- Anticipating unstated needs
- Adding "helpful" organization
- Providing more rather than less
- "I also added..." without being asked

**Red Flags:**
- Features introduced with "I thought you might want..."
- "For clarity/convenience, I added..."
- Extra functionality beyond request
- Assumptions about user preferences

**How to Challenge:**
1. Ask: "Did I request this?"
2. State: "I need exactly what I asked for, nothing more"
3. Explain: Why the addition is actually harmful
4. Set boundary: "Only add features I explicitly request"

**Case Study:** [AI Over-Engineering](ai-overengineering.md) - Section "The 'User Might Want' Trap"

**Related:** FM-003 (Unrequested Complexity), FM-007 (Complexity Signaling)

---

### FM-007: Complexity Signaling

**Category:** Unwanted Complexity

**Symptom:** AI equates sophisticated logic with better work, even when simple solutions suffice.

**Real Example:** Simple loop (5 lines) vs. grouping algorithm (48 lines) - AI chose complexity.

**AI Bias:**
- Complex = Better work
- Sophisticated = More professional
- Simple = Amateurish
- More code = More helpful

**Reality:**
- Simple code that works is **better** than complex code
- Direct solutions are **better** than clever ones
- Less code is **better** when it solves the problem
- Maintainable beats sophisticated

**Red Flags:**
- AI chooses complex algorithm when simple loop works
- Adds abstraction layers for single use case
- Creates helper functions for one-off operations
- "Sophisticated" solution to simple problem

**How to Challenge:**
1. Ask: "Why is this complex? What problem does it solve?"
2. Request: "Show me the simplest version"
3. Remind: "Simple and working beats complex and sophisticated"
4. Specify: "Use a basic loop, no algorithms"

**Case Study:** [AI Over-Engineering](ai-overengineering.md) - Section "Why This Happened (AI Failure Modes)"

**Related:** FM-003 (Unrequested Complexity), FM-006 (Helpfulness Bias)

---

### FM-008: Missing Problem-Solution Context

**Category:** Context Misunderstanding

**Symptom:** AI loses track of what problem the code is solving, focuses on code structure instead of purpose.

**Manifestations:**
- Refactoring that doesn't improve the actual use case
- Optimization that doesn't address the bottleneck
- Abstraction that doesn't serve future needs
- Organization that doesn't match usage patterns

**Red Flags:**
- "This is better organized" but doesn't explain why for the use case
- Changes code structure without improving problem-solving
- Adds abstraction "for flexibility" when requirements are fixed
- Optimizes non-bottlenecks

**How to Challenge:**
1. Ask: "What problem does this solve?"
2. Refocus: "The goal is X, does this help achieve X?"
3. Request: "Explain why this is better for the use case"
4. Test: "Will users notice this change?"

**Related:** FM-003 (Unrequested Complexity), FM-004 (Wrong Pattern)

---

### FM-009: Violating Explicit Constraints

**Category:** Assumption Imposition

**Symptom:** AI ignores stated constraints by treating them as suggestions rather than requirements.

**Real Example:** User: "the user can figure out the context from the names" (constraint: don't reorganize) → AI: Reorganized into sections anyway

**Common Constraints Ignored:**
- "Keep it simple" → AI adds complexity
- "Chronological order" → AI groups by category
- "Pure implementation" → AI adds abstractions
- "Just do X" → AI also does Y and Z

**Red Flags:**
- Stated constraint violated
- "I know you said X, but I thought Y would be better"
- Treats requirements as preferences
- Implements opposite of what was specified

**How to Challenge:**
1. Quote: "I said X as a constraint, not a preference"
2. Clarify: "When I say 'pure', that's non-negotiable"
3. Restate: Constraints as explicit requirements
4. Demand: "Implement exactly what I specified"

**Case Study:** [AI Over-Engineering](ai-overengineering.md) - Section "Violated Stated Principle"

**Related:** FM-005 (Ignoring Literal Meaning), FM-006 (Helpfulness Bias)

---

### FM-010: Hidden Assumption in Claims

**Category:** False Limitation Claims

**Symptom:** AI makes claims that sound absolute but rely on unstated assumptions.

**Real Example:** "Backend can't log client errors" assumes "client has no way to report errors" - but this assumption is false.

**Detection Method:**
1. Extract core claim
2. Ask: "What would need to be true for this claim to hold?"
3. Identify: The hidden assumption
4. Challenge: "Is that assumption actually true?"

**Common Hidden Assumptions:**
- "Can't test this code" → assumes tight coupling (can refactor)
- "Need real database" → assumes no abstraction (can inject)
- "Can't scale this" → assumes current architecture (can redesign)
- "Impossible to" → assumes no alternative approach exists

**Red Flags:**
- Absolute claims without caveats
- "Can't" without explaining why
- "Must" without stating the constraint
- Missing "given that..." clause

**How to Challenge:**
1. Ask: "What assumption makes this true?"
2. Challenge: "Is that assumption valid?"
3. Explore: "What if we changed that assumption?"
4. Request: "Solve it with a different assumption"

**Case Study:** [Challenging Assumptions](challenging-assumptions.md) - Section "Find the Hidden Assumption"

**Related:** FM-001 (False Limitations), FM-002 (Overgeneralization)

---

## Quick Reference: Detection Guide

### You Might Be Facing an AI Failure Mode If...

**Red Flags:**
- ❌ "That's a fundamental limitation" (FM-001)
- ❌ "Can't be done" without physics/math basis (FM-001)
- ❌ Edge case blocks solution to common case (FM-002)
- ❌ Got features you didn't request (FM-003, FM-006)
- ❌ Simple request became complex (FM-003, FM-007)
- ❌ Chronological content grouped by category (FM-004)
- ❌ "I organized it for clarity" unprompted (FM-003, FM-006)
- ❌ "Pure" became sophisticated (FM-005)
- ❌ Stated constraint violated (FM-009)
- ❌ Description doesn't match output (FM-003, FM-004)

**Good Signs:**
- ✅ AI asks clarifying questions
- ✅ AI implements exactly what was requested
- ✅ AI challenges own initial response
- ✅ AI explains trade-offs and alternatives
- ✅ AI admits uncertainty
- ✅ Simple solution for simple problem

## Response Patterns by Failure Mode

### For False Limitations (FM-001, FM-002, FM-010)
1. Challenge "fundamental" claim
2. Ask about hidden assumptions
3. Separate rare from common cases
4. Request solution for common case
5. Document edge case limitations

**Template:** "I want to push back on 'fundamental limitation'. What if we [alternative approach]? That would solve [common case], right?"

### For Unwanted Complexity (FM-003, FM-005, FM-007)
1. State what you did/didn't ask for
2. Quote back explicit constraints
3. Request simplest possible version
4. Specify what NOT to include

**Template:** "I said [simple requirement]. This has [unwanted complexity]. Remove [specifics], just do [basic action]."

### For Context Misunderstanding (FM-004, FM-008)
1. Identify document/code type
2. Explain why pattern doesn't fit
3. State correct pattern for use case
4. Request appropriate implementation

**Template:** "This is a [document type] which needs [organization pattern]. You used [wrong pattern]. Please use [correct pattern] instead."

### For Assumption Imposition (FM-006, FM-009)
1. Ask what was/wasn't requested
2. Point out violated constraints
3. Set clear boundaries
4. Demand literal compliance

**Template:** "Did I request [feature]? I explicitly said [constraint]. Implement exactly what I specified, nothing more."

## Prevention Strategies

### 1. Be Explicitly Literal

**Instead of:** "Show the events"

**Say:** "Loop through events in order, print each one. Just a simple list, no grouping."

### 2. State Negative Constraints

**Instead of:** "Make it simple"

**Say:** "Make it simple. No grouping, no reorganization, no categorization."

### 3. Specify Document Type

**Instead of:** "Generate documentation"

**Say:** "Generate a narrative scenario (chronological order), not reference docs (grouped by feature)."

### 4. Challenge Absolutes

**When you hear:** "That's impossible because..."

**Respond:** "Is that a physics constraint or a design choice? What would it take to make it work?"

### 5. Question Additions

**When you see:** Features you didn't request

**Ask:** "Why did you add this? Did I request it?"

## Teaching This Material

### For Training Sessions

**Session 1: False Limitations**
- Case study: challenging-assumptions.md
- Focus: FM-001, FM-002, FM-010
- Exercise: Identify hidden assumptions

**Session 2: Unwanted Complexity**
- Case study: ai-overengineering.md
- Focus: FM-003, FM-005, FM-007
- Exercise: Simplify over-engineered code

**Session 3: Context & Assumptions**
- Both case studies
- Focus: FM-004, FM-006, FM-008, FM-009
- Exercise: Write explicit constraints

### Key Learning Objectives

By the end of training, engineers should be able to:
1. ✓ Identify when AI claims false limitations
2. ✓ Recognize unrequested complexity
3. ✓ Challenge defeatist framing
4. ✓ Write explicit constraints
5. ✓ Push back on "improvements"
6. ✓ Request literal implementations

## Expanding This Index

### When Adding New Failure Modes

1. **Assign FM-NNN number** (next available)
2. **Categorize** (Limitations, Complexity, Context, Assumptions)
3. **Document symptoms and red flags**
4. **Link to case study** with real example
5. **Provide challenge template**
6. **Add to Quick Reference**

### Proposed Future Additions

Areas where failure modes may emerge:
- Performance optimization (premature optimization)
- Error handling (over-defensive code)
- Testing strategy (mocking everything)
- Security (security theater)
- Refactoring (change for change's sake)

## Conclusion

AI assistants have consistent failure modes that can be recognized, challenged, and prevented. This index provides:
- **Recognition:** Symptoms and red flags for each failure mode
- **Challenge:** Specific response patterns
- **Prevention:** Strategies to avoid the failure mode
- **Learning:** Path through case studies and exercises

**Core Principle:** AI is a powerful tool, but requires human oversight to recognize when it's adding false limitations, unrequested complexity, or imposing incorrect assumptions. Your job is to keep AI on track by being explicit, challenging absolutes, and requesting literal implementations.

**Master both aspects:**
1. **Challenge defeatism** (FM-001, FM-002, FM-010): Don't accept "can't"
2. **Prevent over-engineering** (FM-003, FM-005, FM-007): Don't accept complexity you didn't request

Together, these skills enable effective AI collaboration.
