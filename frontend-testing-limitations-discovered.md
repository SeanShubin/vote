# Frontend Testing Limitations Discovered

## Summary

Initial research (frontend-testing-findings.md) concluded "Outcome A: Full Composable Testing Available." However, during implementation of actual interaction tests, we discovered a critical limitation:

**Raw DOM event dispatch does not trigger Compose for Web's event handlers.**

## What We Attempted

Based on the React `@testing-library` pattern, we attempted to:

1. Render `LoginPage` composable with `FakeApiClient`
2. Set input values via `input.value = 'alice'`
3. Dispatch DOM events: `input.dispatchEvent(new Event('input'))` and `input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }))`
4. Verify that `apiClient.authenticate()` was called

## What Actually Happened

### ✅ Tests That Work

- **`loginPageRendersWithUsernameAndPasswordFields`** - PASSED
  - Verifies LoginPage renders
  - Verifies input fields exist in DOM

- **`loginPageInputsCanBeSet`** - PASSED
  - Verifies we can set `input.value` directly
  - Verifies we can read values back from DOM

### ❌ Tests That Fail

All interaction tests that attempt to trigger authentication fail:

- `loginPageEnterKeyInPasswordFieldTriggersAuthentication` - FAILED
- `loginPageEnterKeyInUsernameFieldTriggersAuthentication` - FAILED
- `loginButtonClickTriggersAuthentication` - FAILED
- `loginSuccessCallbackInvokedWithCorrectToken` - FAILED

## Root Cause Analysis

### The Problem

Compose for Web's event handlers (`onInput`, `onKeyDown`, `onClick`) are managed by the Compose runtime, not raw DOM event listeners. When we dispatch a raw DOM event, the Compose runtime doesn't see it.

### The Evidence

1. **ComposeWebTestingPrototype.exploreEventDispatch()** successfully dispatched click events, BUT:
   - That test used a plain DOM button created with `document.createElement('button')`
   - It used `addEventListener('click', handler)` - raw DOM listener
   - It did NOT test a Compose composable with `onClick { ... }`

2. **LoginPage state management**:
   ```kotlin
   var userName by remember { mutableStateOf("") }
   Input(InputType.Text) {
       value(userName)
       onInput { userName = it.value }  // Compose handler
   }
   ```
   When we do:
   ```kotlin
   js("input.value = 'alice'")  // Changes DOM
   js("input.dispatchEvent(new Event('input'))")  // Doesn't trigger Compose onInput
   ```
   The DOM changes, but `userName` state remains empty because Compose's `onInput` handler wasn't invoked.

3. **handleLogin uses Compose state**:
   ```kotlin
   val handleLogin = {
       // Uses userName and password - Compose state variables
       apiClient.authenticate(userName, password)
   }
   ```
   Even if we successfully dispatch a KeyboardEvent that triggers the handler, it uses the Compose state values (empty strings), not the DOM values we set.

## Revised Capabilities Assessment

### What We CAN Test

1. **Rendering** - Verify composables render correctly
   - Input fields exist
   - Buttons are present
   - Conditional rendering based on props

2. **Direct API Logic** - Test business logic directly (existing `FrontendBehaviorTest` pattern)
   ```kotlin
   val fakeClient = FakeApiClient()
   fakeClient.authenticateResult = Result.success(tokens)
   val result = fakeClient.authenticate("alice", "password")
   assertEquals(1, fakeClient.authenticateCalls.size)
   ```

3. **Callback Verification** - Verify callbacks are invoked when we call them directly (not via UI events)

### What We CANNOT Test

1. **UI Event Handlers** - Cannot trigger Compose's `onInput`, `onKeyDown`, `onClick` handlers from outside
2. **State Transitions** - Cannot verify state changes in response to simulated user interactions
3. **User Workflows** - Cannot test "user types username, presses Enter, authentication is triggered"

## Comparison to Original Assessment

### Original: "Outcome A - Full Composable Testing Available"

Based on:
- ✅ DOM environment exists
- ✅ Can create elements dynamically
- ✅ Can render composables
- ✅ Can dispatch events (tested on plain DOM button, not Compose composable)

### Revised: "Outcome B - Partial Testing Possible"

Reality:
- ✅ Can render composables with fake dependencies
- ✅ Can verify rendering
- ✅ Can query DOM
- ❌ **Cannot simulate events that trigger Compose event handlers** (critical limitation)

## Why the Initial Research Was Incomplete

The prototype tests (ComposeWebTestingPrototype.kt) successfully demonstrated:
1. DOM exists
2. Can render composables
3. Can dispatch events to plain DOM elements

But we didn't test:
- **Dispatching events to Compose composables with Compose event handlers**
- **Verifying that Compose's onInput/onClick/onKeyDown handlers are triggered**

The assumption that "event dispatch works on plain DOM → will work on Compose composables" was incorrect.

## Implications for Test Strategy

### Current Test Coverage (Before This Work)

- 14 tests: FakeApiClient behavior
- 18 tests: Direct API calls with FakeApiClient (bypassing UI)
- 5 tests: Compose rendering prototypes
- Total: 37 tests covering API interactions and rendering

### What We Added (Successful Tests)

- 2 tests: LoginPage rendering verification
- Total: 39 tests

### What We Attempted But Failed

- 4 tests: UI interaction triggering authentication
- These tests compile and run but fail because events don't trigger Compose handlers

## Recommendations

### Option 1: Accept the Limitation

**Approach**: Focus on what we CAN test reliably

**Test strategy**:
- Rendering tests: Verify UI elements exist
- Direct API tests: Continue current FrontendBehaviorTest pattern
- Accept gap: UI event handlers remain untested

**Pros**: No additional complexity, tests are fast and reliable
**Cons**: UI event handlers not covered, keyboard shortcuts not verified

### Option 2: Extract Testable Logic

**Approach**: Separate event handling from business logic

**Refactoring**:
```kotlin
// Extract handler logic to testable function
fun handleLoginLogic(
    userName: String,
    password: String,
    apiClient: ApiClient,
    onSuccess: (String, String) -> Unit
) {
    // Business logic here
}

// Composable uses the function
Input {
    onKeyDown { event ->
        if (event.key == "Enter") {
            handleLoginLogic(userName, password, apiClient, onLoginSuccess)
        }
    }
}

// Test the extracted function
@Test
fun testHandleLoginLogic() = runTest {
    val fakeClient = FakeApiClient()
    handleLoginLogic("alice", "password", fakeClient, { token, user -> })
    assertEquals(1, fakeClient.authenticateCalls.size)
}
```

**Pros**: Business logic is testable, event trigger logic isolated
**Cons**: Refactoring required, event handler wiring still untested

### Option 3: Research Compose for Web Test APIs

**Approach**: Investigate if Compose for Web has built-in testing support we haven't found

**Next steps**:
- Search Compose for Web documentation for testing APIs
- Check if there's a `ComposeTestRule` equivalent for Web
- Look for Compose runtime APIs to trigger recomposition

**Pros**: Might find official solution
**Cons**: May not exist, significant research time

### Option 4: Manual/E2E Testing

**Approach**: Accept that UI interactions require manual testing or E2E tools (Selenium, Playwright)

**Trade-off**: E2E tests are slower, more complex, but verify real browser behavior

## Conclusion

The initial research optimistically concluded full testing was possible, but implementation revealed that **Compose for Web's event system cannot be triggered from outside the Compose runtime using raw DOM event dispatch**.

This is a fundamental architectural limitation, not a solvable implementation detail. We must choose between:
1. Accepting the gap (UI event handlers untested)
2. Refactoring to extract testable logic
3. Using E2E testing for UI interactions
4. Continued research for Compose-specific testing APIs

**Current recommendation**: Option 1 (Accept the Limitation) + verify with user what level of UI testing is actually needed.

The existing test coverage (API interactions with FakeApiClient) may already provide sufficient confidence for the business logic, making UI event testing less critical than initially assumed.
