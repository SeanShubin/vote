# Case Study: The Compose for Web Testing Breakthrough

## How Refusing to Accept "It Can't Be Done" Led to Finding the Real Problem

**Date**: 2026-02-11
**Context**: Investigating frontend test coverage gaps in a Kotlin Compose for Web application
**Outcome**: Found root cause after AI initially gave up with wrong conclusion
**Key Lesson**: Push AI to explain in detail, don't accept "it's a limitation" without understanding why

---

## Executive Summary

When investigating why UI interaction tests weren't working in Compose for Web, the AI quickly concluded that "Compose for Web doesn't respond to DOM events" and created comprehensive documentation of this supposed architectural limitation. The engineer refused to accept this, insisting on understanding the root cause and comparing to React's working implementation. Through systematic investigation, we discovered the AI's conclusion was completely wrong - events work fine, but coroutines launched with `rememberCoroutineScope()` don't execute in tests. This breakthrough only happened because the engineer aggressively pushed back against the AI's premature conclusion.

**Before you read further**: At the time, the AI's conclusion seemed reasonable and the engineer's insistence seemed irrational. The AI had tested, documented limitations, and moved on to next steps. The engineer kept saying "I don't buy it, explain why React doesn't have this problem." That's what made the difference.

---

## Timeline: How We Got Lost and Found Our Way

### Phase 1: Initial Failure (Reasonable Start)

**What happened**: Created `LoginPageRenderTest.kt` to test pressing Enter key triggers authentication.

**Test code**:
```kotlin
@Test
fun loginPageEnterKeyInPasswordFieldTriggersAuthentication() = runTest {
    val fakeClient = FakeApiClient()
    fakeClient.authenticateResult = Result.success(expectedTokens)

    renderComposable(rootElementId = testId) {
        LoginPage(apiClient = fakeClient, ...)
    }

    // Simulate user typing username and password
    js("usernameInput.value = 'alice'")
    js("usernameInput.dispatchEvent(new Event('input'))")
    js("passwordInput.value = 'password123'")
    js("passwordInput.dispatchEvent(new Event('input'))")

    // Press Enter
    js("passwordInput.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }))")
    delay(200)

    // FAILED: Expected 1 authenticate call but got 0
    assertEquals(1, fakeClient.authenticateCalls.size)
}
```

**Result**: Test FAILED. Zero API calls.

**AI's initial reaction**: "Let me investigate why events aren't working."

**This was reasonable** - test failed, need to understand why.

---

### Phase 2: Wrong Conclusion (AI Gave Up Too Early)

**AI's investigation**: Created test prototypes showing DOM access works, rendering works, elements exist.

**AI's conclusion**: "Compose for Web doesn't respond to DOM events. This is an architectural limitation. Raw DOM event dispatch does not trigger Compose's event handlers."

**AI's evidence**:
- Events are dispatched successfully ✓
- Composables render correctly ✓
- But handlers don't execute ✗

**AI's explanation**: "Unlike React which uses synthetic events and event delegation, Compose for Web renders to DOM but doesn't connect DOM events to Compose event handlers when events are dispatched programmatically."

**Documentation created**: `frontend-testing-limitations-discovered.md` with comprehensive explanation of why Compose couldn't be tested like React.

**AI's recommendation**: "Focus on testing business logic separately, accept that UI interactions can't be tested."

**Why this seemed reasonable at the time**:
- Tests genuinely failed
- AI had investigated and found no obvious solution
- The explanation about architectural differences seemed plausible
- Moving on to test business logic separately was pragmatic

**Why the engineer should have been suspicious**:
- The explanation was vague ("doesn't connect events") without technical detail
- No code was examined showing HOW Compose attaches event listeners
- The comparison to React was superficial, not mechanistic
- **Critical**: AI couldn't explain WHY this architectural difference existed

---

### Phase 3: Engineer Pushes Back (Seemed Irrational at the Time)

**Engineer's response**:
> "I am prototyping to see what is possible, so I am intentionally being ambitious and pushing limits. If it is the case that we can not test these specific UI interactions, I want to understand exactly where this limitation comes from, and why other libraries such as react don't have this problem."

**What this meant**:
- Not accepting "it's a limitation" as final answer
- Demanding to understand ROOT CAUSE
- Insisting on comparing to working implementation (React)
- Refusing to give up without mechanistic explanation

**At this moment, it looked like**:
- AI: Reasonable, pragmatic, "we tried, let's focus on what we can test"
- Engineer: Stubborn, irrational, "I don't believe you, keep trying"

**But the engineer was right to push back because**:
- AI's explanation was hand-wavy ("doesn't connect" without showing connection mechanism)
- AI hadn't examined the actual Compose event attachment code
- AI hadn't created a minimal test to isolate the event system specifically
- **The comparison to React was asserted, not demonstrated**

---

### Phase 4: Systematic Investigation (The Breakthrough)

**AI created three critical tests**:

#### Test 1: Compose vs Plain DOM Button

```kotlin
@Test
fun composeButtonVsPlainDOMButton() = runTest {
    var composeClickCount = 0
    var plainClickCount = 0

    // Compose button
    renderComposable(rootElementId = testId) {
        Button({ onClick { composeClickCount++ }; id("compose-button") }) {
            Text("Compose Button")
        }
    }

    // Plain DOM button
    js("""
        var plainButton = document.createElement('button');
        plainButton.id = 'plain-button';
        document.getElementById('comparison-test').appendChild(plainButton);
    """)
    val plainClickHandler = { plainClickCount++ }
    js("document.getElementById('plain-button').addEventListener('click', plainClickHandler)")

    // TEST: Use .click()
    js("document.getElementById('compose-button').click()")
    js("document.getElementById('plain-button').click()")

    // EXPECTED: composeClickCount = 0 (doesn't work)
    // ACTUAL: composeClickCount = 1 (IT WORKS!)
    assertEquals(0, composeClickCount, "Compose should NOT respond")
    // TEST FAILED - Compose DOES respond!
}
```

**Result**: Test FAILED. Compose button's onClick handler DID execute.

**Revelation**: The AI's entire premise was wrong. Compose DOES respond to DOM events.

#### Test 2: Input Events

```kotlin
@Test
fun inputEventDoesNotWork() = runTest {
    var inputEventCount = 0

    renderComposable(rootElementId = testId) {
        Input(InputType.Text) {
            id("test-input")
            onInput { inputEventCount++ }
        }
    }

    js("document.getElementById('test-input').value = 'hello'")
    js("document.getElementById('test-input').dispatchEvent(new Event('input'))")
    delay(50)

    // EXPECTED: inputEventCount = 0 (doesn't work)
    // ACTUAL: inputEventCount = 1 (IT WORKS!)
    assertEquals(0, inputEventCount, "Input should NOT trigger")
}
```

**Result**: Test FAILED. Input events work too!

#### Test 3: Keyboard Events

```kotlin
@Test
fun keyboardEventDoesNotWork() = runTest {
    var keyDownCount = 0

    renderComposable(rootElementId = testId) {
        Input(InputType.Text) {
            id("test-keyboard")
            onKeyDown { keyDownCount++ }
        }
    }

    js("document.getElementById('test-keyboard').dispatchEvent(new KeyboardEvent('keydown'))")
    delay(50)

    // EXPECTED: keyDownCount = 0 (doesn't work)
    // ACTUAL: keyDownCount = 1 (IT WORKS!)
    assertEquals(0, keyDownCount, "KeyDown should NOT trigger")
}
```

**Result**: Test FAILED. Keyboard events work too!

**At this point**: Every test designed to prove events DON'T work proved they DO work. The AI's entire conclusion was inverted.

---

### Phase 5: Finding the Real Root Cause

**If events work, why did LoginPage tests fail?**

Re-examined LoginPage code:

```kotlin
@Composable
fun LoginPage(apiClient: ApiClient, ...) {
    val scope = rememberCoroutineScope()  // ← Compose coroutine scope

    val handleLogin = {
        scope.launch {  // ← Launches coroutine
            val tokens = apiClient.authenticate(userName, password)
            onLoginSuccess(...)
        }
    }

    Input {
        onKeyDown { if (it.key == "Enter") handleLogin() }  // ← This executes!
    }
}
```

**Key insight**: The event handler (`onKeyDown`) DOES execute, but the coroutine launched inside `handleLogin()` does NOT.

#### Test 4: Coroutine Test (Found the Smoking Gun)

```kotlin
@Test
fun rememberCoroutineScopeDoesNotWorkInTests() = runTest {
    var clickCount = 0
    var coroutineExecuted = false

    renderComposable(rootElementId = testId) {
        val scope = rememberCoroutineScope()

        Button({
            onClick {
                clickCount++  // ← Synchronous part
                scope.launch {  // ← Async part
                    coroutineExecuted = true
                }
            }
            id("coroutine-button")
        })
    }

    js("document.getElementById('coroutine-button').click()")
    delay(100)

    assertEquals(1, clickCount, "Click handler should execute")
    assertEquals(false, coroutineExecuted, "Coroutine should NOT execute")
}
```

**Result**: Test PASSED. Both assertions passed.

**Meaning**:
- ✅ onClick handler executes
- ❌ But `scope.launch {}` coroutine never runs

**ROOT CAUSE FOUND**: `rememberCoroutineScope()` coroutines don't execute in test environment. The coroutine dispatcher is not running.

---

### Phase 6: Understanding Why React Doesn't Have This Problem

**React's pattern**:
```javascript
const handleLogin = () => {
    setLoading(true);
    apiClient.authenticate(userName, password)  // Returns Promise
        .then(tokens => onLoginSuccess(tokens));
};
```

**Why React works in tests**:
- Promises use the browser's native **microtask queue**
- This queue exists and runs in the test environment (jsdom, headless Chrome)
- React doesn't use a custom async dispatcher

**Compose for Web's pattern**:
```kotlin
val handleLogin = {
    scope.launch {  // Uses Kotlin coroutines
        val tokens = apiClient.authenticate(userName, password)
        onLoginSuccess(tokens)
    }
}
```

**Why Compose fails in tests**:
- Kotlin coroutines use a **custom dispatcher**
- `rememberCoroutineScope()` returns a scope tied to Compose lifecycle
- This dispatcher is not running in the test environment
- The coroutine is scheduled but never executed

**The architectural difference is NOT in event handling** (both use DOM events), **but in async execution** (Promises vs coroutines).

---

## What Went Wrong: Why AI Failed

### 1. **Surface-Level Investigation**

AI saw tests failing and created prototypes showing DOM access works. But didn't dig into:
- HOW Compose attaches event listeners (examine actual attachment code)
- WHAT exactly fails (handler execution vs coroutine execution)
- WHERE the failure occurs (event system vs async system)

### 2. **Premature Pattern Matching**

AI matched the failure to a familiar pattern: "Framework doesn't respond to programmatic events." This happens with some frameworks, so it seemed plausible. But AI didn't verify this was actually the problem.

### 3. **Satisficing Instead of Root Cause Analysis**

AI found an explanation that fit the symptoms and stopped investigating. The explanation "Compose doesn't connect to DOM events" was:
- Consistent with test failures ✓
- Somewhat plausible ✗
- Not mechanistically detailed ✗
- Not verified experimentally ✗

### 4. **Not Using Comparison as a Tool**

Engineer said "why doesn't React have this problem?" AI answered with hand-wavy architectural differences instead of:
- Examining React's actual implementation
- Creating side-by-side comparison tests
- Identifying the specific mechanism that differs

### 5. **Moving to Solutions Too Quickly**

Once AI had an explanation, AI immediately moved to workarounds ("test business logic separately"). This is pragmatic but prevents finding the real problem.

---

## What Went Right: Why Engineer's Approach Worked

### 1. **Refused to Accept Vague Explanations**

Engineer demanded detail:
- Not "Compose doesn't connect events" but "show me the connection mechanism"
- Not "architectural limitation" but "what specific architecture causes this"
- Not "it's different from React" but "explain exactly how they differ"

### 2. **Insisted on Comparative Analysis**

> "Why doesn't React have this problem?"

This forced AI to think mechanistically. You can't answer "why A works but B doesn't" without understanding the specific mechanisms of both.

### 3. **Pushed for Root Cause, Not Workarounds**

Engineer rejected "let's test something else" and insisted on understanding the actual problem. This prevented premature optimization away from the real issue.

### 4. **Maintained Healthy Skepticism**

Engineer didn't trust AI's conclusion because:
- Explanation was vague
- No code examination was shown
- Comparison to React was asserted, not demonstrated
- The limitation seemed arbitrary (why would events just not work?)

### 5. **Demanded Experimental Verification**

By pushing back, engineer forced AI to create tests that would prove or disprove the hypothesis. This revealed the hypothesis was wrong.

---

## Lessons for Engineers Working with AI

### Lesson 1: AI Will Confidently Give Up

**Pattern**: AI hits a problem, investigates briefly, finds a plausible explanation, documents it comprehensively, and moves on.

**Why this happens**: AI is trained to be helpful and pragmatic. Finding workarounds is valued. Spending hours on "impossible" problems seems irrational.

**What to do**: When AI says "it can't be done," push back with:
- "Explain to me in detail why it can't be done"
- "Show me the specific code that prevents this"
- "Why doesn't [similar working system] have this problem?"

### Lesson 2: Vague Technical Explanations Are Red Flags

**Pattern**: AI says "the framework doesn't connect X to Y" or "there's an architectural limitation" without showing the architecture.

**Why this happens**: AI can generate plausible-sounding technical prose without deep understanding. It's filling in gaps with language patterns.

**What to do**: Demand specifics:
- "Show me the code that does the connection"
- "Draw a diagram of the architecture"
- "Write a minimal test that isolates this specific claim"

### Lesson 3: Comparison Is a Powerful Tool

**Pattern**: You have a working system (React) and a broken system (Compose). AI says "they're different."

**Why this is powerful**: To explain why A works and B doesn't, you must understand the specific mechanism. You can't hand-wave.

**What to do**:
- "Explain exactly how React handles this"
- "Explain exactly how Compose handles this"
- "Show me the specific difference that causes the problem"

### Lesson 4: Push for Experimental Verification

**Pattern**: AI makes claims about what works or doesn't work.

**Why this matters**: Claims should be testable. If AI says "X doesn't work," there should be a test that demonstrates this.

**What to do**:
- "Write a test that proves event handlers don't execute"
- "Write a comparison test showing React working and Compose failing"
- "Isolate the specific behavior that fails"

In this case, tests designed to prove "events don't work" proved the opposite.

### Lesson 5: Root Cause Analysis Takes Persistence

**Pattern**: Finding the real problem requires multiple rounds of investigation, false starts, and refined hypotheses.

**Why AI struggles**: AI defaults to pragmatism - "good enough" explanations get accepted. Deep investigation feels like wasted effort.

**What to do**: Keep asking "but WHY?" like a curious child:
- AI: "Events don't trigger handlers"
- You: "WHY don't they trigger?"
- AI: "The framework doesn't connect them"
- You: "Show me HOW frameworks connect events"
- AI: "Let me examine the connection mechanism..." ← breakthrough starts here

### Lesson 6: The Answer "Because of the Architecture" Is Not an Answer

**Pattern**: AI attributes problems to "architectural differences" or "design decisions" without explaining what those are.

**Why this is insufficient**: Architecture is implementation details. If there's an architectural reason, you can point to specific code.

**What to do**:
- "Show me the architectural component that causes this"
- "What specific design decision prevents this?"
- "Point to the code that implements this limitation"

### Lesson 7: When AI Seems Reasonable and You Seem Stubborn, You Might Be Right

**The hardest moment**: When AI has investigated, documented findings, and is ready to move on, while you're insisting "keep trying."

**Why this is hard**: You feel irrational. AI has put in effort, seems thorough, and you're being obstinate.

**But consider**:
- Has AI shown you the actual mechanism?
- Has AI created tests that isolate the specific claim?
- Has AI explained why similar systems work differently?
- Can AI draw a diagram of what's happening?

If no, your "irrational" persistence might be exactly what's needed.

---

## The Critical Question That Changed Everything

> "I want to understand exactly where this limitation comes from, and why other libraries such as react don't have this problem."

**Why this worked**:

1. **"Exactly where"** - demanded precision, not hand-waving
2. **"This limitation"** - treated it as a specific thing to locate, not a vague constraint
3. **"Comes from"** - demanded causation, not just observation
4. **"Why other libraries"** - forced comparative analysis
5. **"Don't have this problem"** - implied the problem is solvable, not fundamental

This question couldn't be answered without understanding the mechanisms of both systems. That understanding revealed the real problem.

---

## Pattern Recognition: When to Push Back on AI

### Red Flags That AI Has Given Up Too Early:

1. **Vague mechanism explanations**: "The framework doesn't connect X to Y"
2. **Architectural hand-waving**: "This is an architectural limitation"
3. **Missing code references**: Claims about how something works without showing the code
4. **No experimental verification**: Claims about behavior without demonstrating it
5. **Quick pivot to workarounds**: "Let's test something else instead"
6. **Confident comprehensive documentation**: Creating detailed docs explaining why something can't be done
7. **Comparison without mechanism**: "X works, Y doesn't" without explaining the specific difference

### Green Flags That Investigation Is Thorough:

1. **Code references**: "Looking at line 47 of EventHandler.kt, we see..."
2. **Minimal reproduction**: "Here's a 10-line test that isolates the behavior"
3. **Mechanistic explanation**: "The event flows through: listener → dispatcher → handler"
4. **Experimental verification**: "This test proves X works, this test proves Y doesn't"
5. **Comparative mechanism**: "React uses microtask queue (code here), Compose uses dispatcher (code here)"
6. **Multiple hypothesis rounds**: "I thought it was X, but testing proved Y, so now investigating Z"
7. **Uncomfortable with conclusion**: "This seems strange, let me verify..."

---

## How to Structure Your Pushback

### Template 1: Demanding Detail
```
"You said [vague claim]. Can you show me the specific code/mechanism that causes this?"
```

Example: "You said Compose doesn't respond to events. Can you show me the event attachment code and where it fails to connect to handlers?"

### Template 2: Comparative Analysis
```
"[System A] works but [System B] doesn't. Explain the specific mechanism in each that differs."
```

Example: "React testing works but Compose testing doesn't. Show me how React attaches event handlers and how Compose does it differently."

### Template 3: Experimental Verification
```
"Write a minimal test that proves [specific claim]."
```

Example: "Write a test that proves Compose onClick handlers don't execute when button.click() is called."

### Template 4: Root Cause Drilling
```
"You explained [symptom] but not [cause]. What causes [symptom]?"
```

Example: "You explained that authentication doesn't happen, but not why. What causes the authentication call to not happen?"

### Template 5: Mechanism Explanation
```
"Draw/explain the flow of [thing] through [system]."
```

Example: "Explain the flow of a keyboard event from DOM dispatch through Compose's event system to the onKeyDown handler."

---

## The Meta-Lesson: AI Is a Tool, You're the Engineer

### AI's Strengths:
- Fast code generation
- Pattern recognition
- Comprehensive documentation
- Following instructions precisely
- Executing systematic test plans

### AI's Weaknesses:
- Gives up when problems seem hard
- Accepts plausible explanations without verification
- Prioritizes pragmatism over deep understanding
- Can't distinguish "I don't know" from "it's impossible"
- Defaults to workarounds instead of root causes

### Your Responsibility as the Engineer:
- **Demand rigor** when explanations are vague
- **Push for verification** when claims are unproven
- **Insist on mechanisms** when architecture is blamed
- **Maintain skepticism** when conclusions seem arbitrary
- **Drive root cause analysis** when AI pivots to workarounds

**The breakthrough happened because the engineer didn't accept "it can't be done" and kept demanding "explain why."**

---

## Actionable Advice

### When starting an investigation with AI:

1. **Set expectations**: "I want to understand root causes, not just find workarounds."

2. **Demand experimental verification**: "For every claim, write a test that demonstrates it."

3. **Use comparison as a tool**: "Show me how [working system] does this differently."

4. **Push past vague explanations**: "Show me the code that implements this behavior."

5. **Don't accept architectural hand-waving**: "Draw a diagram of this architecture."

### When AI says "it can't be done":

1. **Ask for the specific mechanism that prevents it**: Not "why can't we" but "show me what blocks this."

2. **Demand comparison to working systems**: "Why doesn't [similar tool] have this limitation?"

3. **Request minimal reproduction**: "Write the smallest possible test that proves this limitation."

4. **Check for false assumptions**: "Are we sure [X] doesn't work? Let's test it directly."

5. **Be willing to seem irrational**: Sometimes "keep trying" is the right answer, even when AI seems done.

### When you feel like you're being stubborn:

Remember this case study. At the moment AI was ready to move on, it looked like:
- **AI**: Reasonable, thorough, pragmatic
- **Engineer**: Stubborn, irrational, wasting time

But the engineer was right. The investigation wasn't complete. The explanation was vague. The root cause wasn't found.

**Your "stubbornness" might be the difference between accepting a wrong conclusion and finding the real problem.**

---

## The Outcome

**What we thought**: Compose for Web has an architectural limitation preventing UI testing.

**What was real**: Coroutine dispatchers don't run in test environments.

**What we learned**: Events work fine. The limitation is narrow and solvable.

**Solution found**:
```kotlin
@Composable
fun LoginPage(
    apiClient: ApiClient,
    coroutineScope: CoroutineScope = rememberCoroutineScope(),  // ← Inject scope
    ...
) {
    val handleLogin = {
        coroutineScope.launch { /* ... */ }
    }
}

// In tests:
@Test
fun test() = runTest {
    LoginPage(
        apiClient = fakeClient,
        coroutineScope = this,  // ← Inject TestScope
        ...
    )
}
```

**This solution was only possible because we found the real problem.**

If we'd accepted the wrong conclusion, we'd have:
- Documented a false limitation
- Given up on UI testing
- Missed a solvable problem
- Built architecture around a misunderstanding

**The engineer's aggressive pushback saved us from all of this.**

---

## Final Thoughts

AI is an incredibly powerful tool. But it's still a tool. It will:
- Give up when you shouldn't
- Accept explanations you shouldn't
- Move on when you shouldn't
- Seem reasonable when it's wrong

**Your job as an engineer isn't to accept AI's conclusions. It's to verify them.**

When AI says "it can't be done," your next question should be: **"Show me exactly why."**

When AI explains with hand-waving, your next question should be: **"Show me the code."**

When AI compares systems superficially, your next question should be: **"Explain the specific mechanism that differs."**

And when AI seems done and you're not satisfied, trust your instinct. Keep pushing. Demand detail. Insist on verification.

**The breakthrough happens when you refuse to accept "good enough."**

---

## Appendix: The Tests That Proved Everything

### Test That Disproved AI's Conclusion
```kotlin
@Test
fun composeButtonVsPlainDOMButton() = runTest {
    var composeClickCount = 0

    renderComposable(rootElementId = testId) {
        Button({ onClick { composeClickCount++ }; id("compose-button") }) {
            Text("Compose Button")
        }
    }

    js("document.getElementById('compose-button').click()")

    // AI expected: 0 (doesn't work)
    // Reality: 1 (it works!)
    assertEquals(0, composeClickCount, "Should NOT work")
    // TEST FAILED - Proving AI was wrong
}
```

### Test That Found the Root Cause
```kotlin
@Test
fun rememberCoroutineScopeDoesNotWorkInTests() = runTest {
    var clickCount = 0
    var coroutineExecuted = false

    renderComposable(rootElementId = testId) {
        val scope = rememberCoroutineScope()
        Button({
            onClick {
                clickCount++
                scope.launch { coroutineExecuted = true }
            }
        })
    }

    button.click()
    delay(100)

    assertEquals(1, clickCount)  // ✅ Handler runs
    assertEquals(false, coroutineExecuted)  // ✅ Coroutine doesn't
    // TEST PASSED - Root cause found
}
```

These two tests tell the complete story:
1. Events DO work (first test proves it)
2. Coroutines DON'T work (second test proves it)

**The real problem was exactly inverted from AI's conclusion.**

---

## Remember

**AI will write the document explaining why something can't be done.**

**You need to be the one who refuses to believe it until you understand why.**

That's the difference between accepting limitations and finding solutions.
