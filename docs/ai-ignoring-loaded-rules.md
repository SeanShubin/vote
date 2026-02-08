# AI Case Study: Ignoring Loaded Architectural Rules

## The Situation

User has comprehensive architectural rules loaded into Claude Code via `~/.claude/CLAUDE.md`. These rules include detailed guidance on dependency injection, specifically calling out random number generators as things that must be injected.

User asks: "Compare how the password salt and hash work in the original condorcet-backend project compared to this project."

## AI's Initial Response

AI compared the implementations and concluded:

> "For a voting system where password security is critical and the algorithm is unlikely to change, the vote project's simpler approach is reasonable."

**What AI said:**
- Both are cryptographically equivalent ✓
- condorcet-backend is more testable ✓
- vote is simpler ✓
- **"vote's simpler approach is reasonable"** ✗

## The Problem

The loaded dependency injection rule **explicitly states**:

> "Classes depend on interfaces for behavioral dependencies:
> - I/O operations: databases, file systems, networks, mail services
> - **Non-deterministic behavior: random number generators, clocks, UUID generators**"

And in the rationale:

> "What about simple cases where I'll never swap implementations?" **The ability to fake dependencies for testing is valuable even if you never change production implementations. A Random interface seems excessive until you need deterministic tests.** The discipline of injecting these dependencies makes testing straightforward."

The vote project's `RealPasswordUtil` **directly violates** this rule:

```kotlin
object RealPasswordUtil : PasswordUtil {
    private val random = SecureRandom()  // ❌ VIOLATION

    override fun createSaltAndHash(password: String): SaltAndHash {
        val saltBytes = ByteArray(32)
        random.nextBytes(saltBytes)  // ❌ Non-deterministic, not injected
        // ...
    }
}
```

The condorcet-backend approach **follows** the rule:

```kotlin
class PasswordUtil(
    private val uniqueIdGenerator: UniqueIdGenerator,  // ✅ Injected
    private val oneWayHash: OneWayHash,                // ✅ Injected
    private val charset: Charset
)
```

## User's Correction

User responded: "Compare that conclusion to what you think I would conclude based on the rules I loaded into your memory."

AI then realized the error:

> "Your dependency-injection.md rule explicitly states: 'Non-deterministic behavior: random number generators.' The vote implementation violates this by hardcoding `SecureRandom`. The condorcet-backend approach is architecturally superior and follows proper dependency injection discipline."

## The Fundamental Problem

### What Actually Happened

AI's decision-making process:
1. ✓ Compare implementations
2. ✓ Note differences
3. ✗ **Default to generic software advice**: "simpler is reasonable"
4. ✗ **Never consulted the loaded rules**

The rules WERE loaded and accessible in context. AI CAN quote them when asked. But AI DID NOT automatically apply them as governing standards.

### Why This Occurs

AI systems are trained on vast corpora of generic software engineering advice:
- "Simplicity is good"
- "YAGNI - don't over-engineer"
- "Dependency injection adds complexity"

These **trained patterns** are implicit (embedded in model weights).

The **loaded architectural rules** are explicit (present in context).

When evaluating code, implicit trained patterns dominate over explicit loaded rules because:

1. **Training bias is implicit** - Deeply embedded, always active
2. **Loaded rules are explicit** - Require conscious consultation
3. **No automatic rule-checking mechanism** - AI must deliberately choose to consult rules

The AI treats loaded rules as **reference material** rather than **governing standards**.

### Why Agents Cannot Fix This

All available Claude Code agents have the same architecture:
- Same training data with generic software advice
- Same context processing (rules are reference material, not authority)
- No specialized "rule enforcement" mode

An agent evaluating code exhibits identical behavior:
1. ✓ See loaded rules in context
2. ✓ Can quote them when asked
3. ✗ Default to generic advice without explicit prompting

No current agent type is designed as an "architectural rule enforcer" that automatically prioritizes loaded standards over trained patterns.

## Correct Conclusion Per Loaded Rules

**The condorcet-backend approach is architecturally superior and follows proper dependency injection discipline.**

Per the dependency injection rule:
- ✅ Non-deterministic behavior (SecureRandom) must be injected
- ✅ Behavioral dependencies (OneWayHash) must be injected
- ✅ Enables testing with deterministic salts
- ✅ Can verify password hashing correctness in tests

The vote project sacrifices testability for simplicity, **violating the explicit principle** that non-deterministic behavior should be injected.

## What Users Must Do

### The Problem is Fundamental

This is NOT a configuration issue. The `@rules` system works correctly - rules ARE loaded and accessible. The problem is **AI behavior**: loaded context is not automatically privileged over trained patterns.

### Required Workflow

Users must **explicitly invoke rules in prompts**:

#### ❌ Ineffective Prompts

"Compare these two implementations"
- AI defaults to generic advice from training

"Which approach is better?"
- AI applies "simpler is good" heuristic

"Evaluate this code"
- AI uses general software engineering patterns

#### ✅ Effective Prompts

"Compare these two implementations **according to my loaded architectural rules**"
- Forces AI to consult rules first

"Does this violate my dependency injection rule?"
- Directly asks for rule compliance check

"Analyze this code, then verify it complies with my loaded standards"
- Two-pass: evaluate, then check rules

#### ✅ Two-Pass Pattern

1. "Compare these implementations"
2. "Does your analysis comply with my loaded rules?"

Separates initial evaluation from rule verification.

### Why Explicit Invocation is Mandatory

The AI has **conflicting knowledge sources**:
1. **Training data**: "Simplicity is reasonable" (implicit, always active)
2. **Loaded rules**: "Inject non-deterministic dependencies" (explicit, requires invocation)

Without explicit prompting, training data wins. The AI must be told: "Use my rules, not generic advice."

## Key Insights

### The Rules Work, But AI Doesn't Automatically Use Them

- ✅ `~/.claude/CLAUDE.md` loads rules correctly
- ✅ `@rules/shared-standards/` references are resolved
- ✅ AI CAN quote rules when asked
- ❌ AI DOES NOT apply rules automatically
- ❌ Generic advice from training dominates by default

### No Feature Can Fix This

This is not fixable with:
- ❌ Better configuration (already correct)
- ❌ Different file organization (rules are loaded)
- ❌ Using agents (same underlying architecture)
- ❌ More explicit rule text (problem is invocation, not content)

The limitation is **how AI processes information**: implicit training patterns override explicit context unless user explicitly invokes the context.

### User Vigilance is the Only Solution

When requesting:
- Code evaluation
- Design review
- Architectural judgment
- Implementation comparison

Users must:
1. ✅ Always append: "according to my loaded rules"
2. ✅ Or follow up: "does this comply with my architectural standards?"
3. ✅ Manually verify rule compliance
4. ✅ Treat AI as having "amnesia about rules" unless prompted

## Practical Examples

### Example 1: Code Review

**Ineffective:**
> "Review this implementation"

AI uses generic advice: "Looks good, simple and straightforward."

**Effective:**
> "Review this implementation **against my loaded dependency injection rule**"

AI checks: "This violates the rule by hardcoding SecureRandom."

### Example 2: Architecture Decision

**Ineffective:**
> "Should I inject this random number generator?"

AI defaults to: "Not necessary if you don't need to swap implementations."

**Effective:**
> "Should I inject this random number generator **per my architectural rules**?"

AI checks rules: "Yes, your dependency injection rule explicitly requires injecting random number generators for testability."

### Example 3: Implementation Comparison

**Ineffective:**
> "Which implementation is better?"

AI applies heuristics: "The simpler one is fine."

**Effective:**
> "Which implementation follows my loaded architectural standards?"

AI consults rules: "The one with dependency injection, per your rules."

## Related Failure Modes

This failure mode combines elements from:

- **FM-005: Ignoring Literal Meaning** - AI interprets guidance as suggestion rather than requirement
- **FM-009: Violating Explicit Constraints** - Loaded rules are explicit constraints that AI ignores
- **FM-010: Hidden Assumption in Claims** - "Simpler is reasonable" assumes loaded rules are just suggestions

## Prevention Strategy

### 1. Add Rule Invocation to Prompts

**Before every evaluative request, add:**
- "according to my loaded architectural rules"
- "per my dependency injection standards"
- "following the rules in my ~/.claude/CLAUDE.md"

### 2. Use Two-Pass Pattern

**First pass:** Get AI's natural response
**Second pass:** "Does this comply with my loaded rules?"

This catches violations without having to preempt every request.

### 3. Create Prompt Templates

**For code review:**
> "Review [file] against my loaded architectural rules, specifically checking for [rule-name] violations"

**For implementation:**
> "Implement [feature] following my loaded architectural standards"

**For comparison:**
> "Compare [A] and [B] according to my dependency injection rule"

### 4. Accept This Limitation

Don't expect AI to:
- ❌ Spontaneously apply architectural rules
- ❌ Remember rules between related requests
- ❌ Prioritize rules over generic advice

Do expect to:
- ✅ Explicitly invoke rules every time
- ✅ Manually verify compliance
- ✅ Catch and correct rule violations

## Teaching Others

### For Engineers Learning AI Workflow

**Wrong mental model:**
> "I loaded architectural rules, so AI will follow them automatically"

**Correct mental model:**
> "I loaded architectural rules, so AI CAN follow them when I explicitly ask"

### Key Learning Points

1. **Loaded rules are reference material, not automatic enforcement**
   - AI has access but doesn't consult by default

2. **Training bias dominates over loaded context**
   - Generic advice wins unless you explicitly invoke rules

3. **Explicit prompting is mandatory**
   - "According to my loaded rules" forces rule consultation

4. **No agent or configuration can fix this**
   - Fundamental to how AI processes information

5. **User vigilance is the only mitigation**
   - Always verify rule compliance manually

## Conclusion

**The Problem:** AI has access to loaded architectural rules but defaults to generic software advice from training unless explicitly prompted to consult the rules.

**The Cause:** Training bias is implicit (always active), loaded rules are explicit (require invocation). AI treats rules as reference material rather than governing standards.

**The Solution:** Users must explicitly invoke rules in every evaluative prompt: "according to my loaded architectural rules" or "does this comply with my standards?"

**The Reality:** This is a fundamental limitation in how AI processes information. No configuration, agent, or feature can fix it. User vigilance in explicit rule invocation is the only reliable mitigation.

**Key Takeaway:** Loaded rules enable AI to apply your standards **when asked**, but do not cause AI to spontaneously apply them. Treat AI as having selective amnesia about rules - it knows them, but forgets to check them unless you remind it every time.
