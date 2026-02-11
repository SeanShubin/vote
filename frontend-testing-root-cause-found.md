# Frontend Testing Root Cause Analysis

## Executive Summary

**Initial Hypothesis**: Compose for Web doesn't respond to DOM events (WRONG)
**Actual Root Cause**: `rememberCoroutineScope()` coroutines don't execute in test environment

**Status**: ✅ **SOLVED** - UI interactions are testable by injecting CoroutineScope parameter

---

## Investigation Journey

### Phase 1: Initial Failure

Attempted to test LoginPage with DOM event dispatch:
- Set input values via DOM
- Dispatched keyboard events
- **Result**: Authentication never called (0 API calls)

**Initial conclusion**: "Compose doesn't respond to DOM events" ❌ WRONG

### Phase 2: Testing the Hypothesis

Created investigation tests to prove Compose doesn't respond to events:

**Test 1: Button Click**
```kotlin
Button({ onClick { clickCount++ } })
button.click()
```
**Expected**: clickCount = 0 (Compose doesn't respond)
**Actual**: clickCount = 1 (Compose DOES respond!) ✅

**Test 2: Input Event**
```kotlin
Input { onInput { inputValue = it.value } }
input.dispatchEvent(new Event('input'))
```
**Expected**: onInput not triggered
**Actual**: onInput WAS triggered! ✅

**Test 3: Keyboard Event**
```kotlin
Input { onKeyDown { keyCount++ } }
input.dispatchEvent(new KeyboardEvent('keydown'))
```
**Expected**: onKeyDown not triggered
**Actual**: onKeyDown WAS triggered! ✅

**Conclusion**: Compose for Web DOES respond to DOM events! Our hypothesis was completely wrong.

### Phase 3: Finding the Real Cause

If events work, why doesn't authentication happen? Re-examined LoginPage logic:

```kotlin
@Composable
fun LoginPage(...) {
    val scope = rememberCoroutineScope()  // ← Compose coroutine scope

    val handleLogin = {
        scope.launch {  // ← Launches coroutine
            val tokens = apiClient.authenticate(userName, password)
            onLoginSuccess(...)
        }
    }

    Input {
        onKeyDown { if (it.key == "Enter") handleLogin() }  // ← Event fires
    }
}
```

**Key insight**: The event handler (`onKeyDown`) DOES execute, but the coroutine launched inside `handleLogin()` does NOT.

### Phase 4: Proving Coroutine Failure

**Test**:
```kotlin
val scope = rememberCoroutineScope()
var coroutineExecuted = false

Button({
    onClick {
        clickCount++  // ← This executes
        scope.launch {
            coroutineExecuted = true  // ← This does NOT execute
        }
    }
})

button.click()
delay(100)

assertEquals(1, clickCount)  // ✅ Passes
assertEquals(false, coroutineExecuted)  // ✅ Passes - coroutine never ran!
```

**Result**:
- onClick handler executes ✅
- But scope.launch{} coroutine never runs ❌

---

## Root Cause Explanation

### Why Coroutines Don't Execute

`rememberCoroutineScope()` returns a CoroutineScope tied to the Compose lifecycle. In a browser, this scope is tied to the browser's event loop and recomposition system.

In the test environment (Karma/headless Chrome running Kotlin/JS tests):
1. Compose renders to DOM ✅
2. Event handlers attach correctly ✅
3. DOM events trigger handlers ✅
4. But `rememberCoroutineScope()` coroutines are never dispatched ❌

**The coroutine dispatcher is not running in the test environment.**

### Why React Doesn't Have This Problem

React's event handling is synchronous:
```javascript
const handleLogin = () => {
    setLoading(true);
    apiClient.authenticate(userName, password)  // Promise-based, but handler is sync
        .then(tokens => onLoginSuccess(tokens));
};
```

Even though `.authenticate()` returns a Promise (asynchronous), the **event handler itself** runs synchronously and kicks off the async work. React doesn't use a separate coroutine dispatcher - it uses the browser's native Promise/microtask queue.

Compose for Web uses Kotlin coroutines with a custom dispatcher that apparently doesn't run in the test environment.

---

## Comparison Table

| Aspect | React Testing Library | Compose for Web (Current) |
|--------|----------------------|---------------------------|
| **DOM events trigger handlers?** | ✅ Yes | ✅ Yes |
| **Event handlers execute synchronously?** | ✅ Yes | ✅ Yes |
| **Async work in handlers?** | ✅ Promises work | ❌ Coroutines don't work |
| **Why async works/fails?** | Uses browser Promise queue | Uses Compose coroutine dispatcher (not running in tests) |

---

## The Solution

### Option 1: Avoid `rememberCoroutineScope()` in Testable Code

Extract async logic to accept a callback or use a different coroutine scope:

**Before (Untestable)**:
```kotlin
@Composable
fun LoginPage(apiClient: ApiClient, ...) {
    val scope = rememberCoroutineScope()  // ← Won't work in tests

    val handleLogin = {
        scope.launch {  // ← Never executes in tests
            val tokens = apiClient.authenticate(userName, password)
            onLoginSuccess(tokens)
        }
    }

    Input { onKeyDown { if (it.key == "Enter") handleLogin() } }
}
```

**After (Testable)**:
```kotlin
@Composable
fun LoginPage(
    apiClient: ApiClient,
    coroutineScope: CoroutineScope = rememberCoroutineScope(),  // ← Inject scope
    ...
) {
    val handleLogin = {
        coroutineScope.launch {
            val tokens = apiClient.authenticate(userName, password)
            onLoginSuccess(tokens)
        }
    }

    Input { onKeyDown { if (it.key == "Enter") handleLogin() } }
}

// In tests
@Test
fun test() = runTest {  // ← runTest provides TestScope
    renderComposable {
        LoginPage(
            apiClient = fakeClient,
            coroutineScope = this,  // ← Inject TestScope
            ...
        )
    }

    // Now coroutines will execute in the test dispatcher
}
```

**Key insight**: `runTest` provides a `TestScope` that controls coroutine execution. By injecting this scope instead of using `rememberCoroutineScope()`, we can test async code.

### Option 2: Make `apiClient.authenticate()` Synchronous in Tests

If we don't need to test async behavior, make the fake synchronous:

```kotlin
class FakeApiClient : ApiClient {
    override suspend fun authenticate(user: String, pass: String): Tokens {
        // Just return immediately instead of actually suspending
        authenticateCalls.add(AuthenticateCall(user, pass))
        return authenticateResult.getOrThrow()
    }
}
```

But this doesn't help because the **coroutine itself** isn't executing, so even a "synchronous" suspend function never runs.

### Option 3: Extract Logic to Non-Composable Functions

Separate business logic from UI:

```kotlin
// Testable business logic (not a Composable)
suspend fun performLogin(
    userName: String,
    password: String,
    apiClient: ApiClient
): Result<Tokens> {
    return try {
        val tokens = apiClient.authenticate(userName, password)
        Result.success(tokens)
    } catch (e: Exception) {
        Result.failure(e)
    }
}

// Test the business logic
@Test
fun testPerformLogin() = runTest {
    val result = performLogin("alice", "password", fakeClient)
    assertEquals(1, fakeClient.authenticateCalls.size)
}

// UI just calls the business logic
@Composable
fun LoginPage(...) {
    val scope = rememberCoroutineScope()

    val handleLogin = {
        scope.launch {
            val result = performLogin(userName, password, apiClient)
            // ... handle result
        }
    }
}
```

This tests the **business logic** but still doesn't test the **UI interaction triggering the logic**.

---

## Available Approaches

Multiple approaches are viable depending on testing goals:

1. **Inject CoroutineScope** for full UI interaction tests (Option 1)
   - Tests complete user workflows through rendered UI
   - Verifies event handlers trigger async operations
   - Requires adding `coroutineScope` parameter to Composables
   - Production code uses default, tests inject `TestScope`

2. **Extract business logic** to testable functions (Option 3)
   - Tests business logic independently from UI
   - Simpler test setup, no UI rendering needed
   - Doesn't verify UI events trigger the logic
   - Good for complex validation, error handling, data transformations

3. **Hybrid solution**
   - Extract business logic for unit testing
   - Inject CoroutineScope for critical UI integration tests
   - Balance between coverage and test complexity

---

## Key Findings

### Initial Hypothesis (Incorrect)
1. ❌ Compose for Web doesn't respond to DOM events
2. ❌ Architectural differences prevent event testing
3. ❌ Testing UI interactions like React is impossible

### Actual Root Cause (Correct)
1. ✅ Compose for Web DOES respond to DOM events
2. ✅ Event handlers execute correctly in tests
3. ✅ The problem is **coroutine dispatching**, not event handling
4. ✅ Solution: Inject TestScope or extract business logic

### Why This Matters

**UI interactions are testable.** The coroutine limitation has a straightforward workaround.

Testing approaches:
- Test business logic separately (extract from composables)
- Test event handlers trigger correctly (events work in tests)
- For UI integration tests, inject CoroutineScope with TestScope

This is fundamentally different from "UI testing is impossible" - it's "UI testing requires understanding Compose's coroutine system."

---

## Test Strategy

### What Can Be Tested (Confirmed Working)

1. ✅ **Rendering** - Composables render correctly
2. ✅ **Event handlers trigger** - onClick, onInput, onKeyDown all fire
3. ✅ **State updates from events** - Direct state changes work
4. ✅ **Synchronous logic** - Pure functions, immediate state updates
5. ✅ **Business logic** - Extract to suspend functions, test with runTest

### What Requires Special Handling

6. ⚠️ **Async event handlers** - Inject CoroutineScope parameter
7. ⚠️ **Integration tests** - Use TestScope from runTest for coroutine execution

### What's NOT the Problem

- ❌ DOM event dispatch (works fine)
- ❌ Compose event system (works fine)
- ❌ Event handler execution (works fine)

---

## Comparison to React Testing

| Aspect | React | Compose for Web |
|--------|-------|-----------------|
| Events work in tests? | ✅ | ✅ |
| Handlers execute? | ✅ | ✅ |
| Async in handlers? | ✅ (Promises) | ⚠️ (Coroutines need special handling) |
| Test complexity | Low | Medium (must understand coroutines) |

The gap is **much smaller** than initially believed. It's not "impossible to test" - it's "requires understanding Compose coroutines."

---

## Implemented Solution

We implemented **Option 1: Inject CoroutineScope with default parameter**.

### Pattern

**Composable Component:**
```kotlin
@Composable
fun LoginPage(
    apiClient: ApiClient,
    onLoginSuccess: (String, String) -> Unit,
    onNavigateToRegister: () -> Unit,
    coroutineScope: CoroutineScope = rememberCoroutineScope()  // ← Injectable with default
) {
    var userName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    val handleLogin = {
        if (!isLoading) {
            isLoading = true
            coroutineScope.launch {  // ← Uses injected scope
                val tokens = apiClient.authenticate(userName, password)
                onLoginSuccess(tokenJson, userName)
            }
        }
    }

    Input(InputType.Password) {
        onKeyDown { event ->
            if (event.key == "Enter") handleLogin()
        }
    }
}
```

**Test:**
```kotlin
@Test
fun loginPageEnterKeyTriggersAuthentication() = runTest {
    val fakeClient = FakeApiClient()
    fakeClient.authenticateResult = Result.success(expectedTokens)

    renderComposable(rootElementId = testId) {
        LoginPage(
            apiClient = fakeClient,
            onLoginSuccess = { _, _ -> loginSuccessCalled = true },
            onNavigateToRegister = { },
            coroutineScope = this@runTest  // ← Inject TestScope
        )
    }

    // Dispatch keyboard event
    js("""
        var passwordInput = document.querySelector('input[type="password"]')
        passwordInput.value = 'password123'
        passwordInput.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }))
    """)

    // Verify authentication was called
    assertEquals(1, fakeClient.authenticateCalls.size)
}
```

### Key Points

- **Production code**: Uses default `rememberCoroutineScope()` - no changes needed at call sites
- **Test code**: Injects `TestScope` from `runTest` - coroutines execute in test dispatcher
- **Pattern applies to**: Any Composable using `rememberCoroutineScope().launch { ... }`

**Result**: Full UI interaction testing (keyboard events, button clicks, async operations) works correctly.
