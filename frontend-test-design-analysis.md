# Frontend Test Design Analysis

## Purpose

Evaluate how well our UI interaction tests serve the dual purpose of:
1. **Complete coverage** - Testing what the application does
2. **Implementation independence** - Not caring about how it does it

## Current Test Quality Assessment

### ✅ Strengths

**1. Testing at the I/O Boundary**
- Tests use `FakeApiClient` to verify API calls were made
- This is the correct abstraction level - we test "did authentication happen?" not "how is authentication implemented?"
- Internal refactoring doesn't break tests as long as the API contract is preserved

**2. Complete User Flow Coverage**
- We render actual composables (not mocking internal components)
- We simulate real user interactions (typing, clicking, pressing Enter)
- We verify end-to-end behavior works

**3. Injectable Test Dependencies**
- `coroutineScope` injection enables testing without changing production behavior
- `FakeApiClient` substitutes I/O boundaries without mocking internal collaborators
- Following the staged dependency injection pattern from the project

### ⚠️ Weaknesses (Implementation Detail Coupling)

Our tests have **brittle coupling** to implementation details:

#### 1. CSS Selector Coupling

```kotlin
// Current approach - tightly coupled to DOM structure
val passwordInput = js("document.querySelector('#test-id input[type=\"password\"]')")
```

**Problem**: If we change the HTML structure (wrap input in a div, use a custom component), tests break even though **what the app does** hasn't changed.

**What would be better**:
```kotlin
// Query by user-visible attribute
val passwordInput = js("document.querySelector('#test-id input[placeholder=\"Password\"]')")
```

This is more stable because users identify fields by placeholder text, not by type attribute.

#### 2. Element Ordering Assumptions

```kotlin
// Current approach - assumes first button is "Login"
js("""
    var buttons = document.querySelectorAll('#test-id button')
    buttons[0].click()
""")
```

**Problem**: If we reorder buttons (put "Register" before "Login"), tests break even though login functionality works fine.

**What would be better**:
```kotlin
// Query by button text (how users identify buttons)
js("""
    var buttons = Array.from(document.querySelectorAll('#test-id button'))
    var loginButton = buttons.find(btn => btn.textContent.trim() === 'Login')
    loginButton.click()
""")
```

#### 3. Low-Level Event Dispatch

```kotlin
// Current approach - knows about DOM event internals
passwordInput.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }))
```

**Problem**: This couples tests to how Compose for Web handles events. If the event system changes, tests break.

**Trade-off**: We **need** this level for Compose for Web testing. Unlike React Testing Library which has user-event helpers, Compose for Web requires direct DOM manipulation.

## The React Testing Library Standard

React Testing Library follows: **"The more your tests resemble the way your software is used, the more confidence they can give you."**

```javascript
// React test example
const { getByPlaceholderText, getByRole } = render(<LoginForm />)

await userEvent.type(getByPlaceholderText('Username'), 'alice')
await userEvent.type(getByPlaceholderText('Password'), 'password123')
await userEvent.click(getByRole('button', { name: /login/i }))

expect(mockApi.authenticate).toHaveBeenCalledWith('alice', 'password123')
```

This test:
- Finds elements **how users find them** (by placeholder text, button label)
- Would survive refactoring input components
- Would survive reordering buttons
- Doesn't care about DOM structure or event internals
- Uses high-level user action helpers (`userEvent.type`, `userEvent.click`)

## Comparison: Our Tests vs React Testing Library

| Aspect | React Testing Library | Our Tests | Impact |
|--------|---------------------|-----------|--------|
| **Element queries** | By role, label, placeholder | By CSS selector, type attribute | **Medium** - More brittle to structure changes |
| **Event dispatch** | High-level user actions | Low-level DOM events | **Low** - Necessary for Compose for Web |
| **Button identification** | By visible text/label | By position (buttons[0]) | **High** - Very brittle |
| **Input identification** | By placeholder/label | By type attribute | **Medium** - Fairly brittle |
| **Verification** | Mock API at boundary | Fake API at boundary | **Same** - Both good |

## What Makes Tests Brittle

### High Priority (Fix These)

**1. Button Position Assumptions**
```kotlin
// BAD: Assumes button order
buttons[0].click()  // Will break if buttons are reordered

// GOOD: Query by visible text
var loginButton = buttons.find(btn => btn.textContent.includes('Login'))
loginButton.click()
```

**2. Element Type-Based Queries**
```kotlin
// BAD: Couples to input type
input[type="text"]  // Will break if we use custom components

// GOOD: Query by placeholder
input[placeholder="Username"]  // Matches how users identify fields
```

### Medium Priority (Nice to Have)

**3. Element Structure Assumptions**
```kotlin
// BAD: Assumes specific DOM nesting
'#test-id div.form input'

// GOOD: Query from root with semantic attributes
'#test-id input[placeholder="Username"]'
```

### Low Priority (Acceptable Trade-offs)

**4. Direct Event Dispatch**
```kotlin
// This is fine - necessary for Compose for Web
input.dispatchEvent(new Event('input'))
input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }))
```

Unlike React, Compose for Web doesn't have testing helpers, so we must use low-level event dispatch. This is an acceptable trade-off.

## Recommendations

### Quick Wins (High Value, Low Effort)

**1. Query buttons by visible text instead of position**

Current approach:
```kotlin
js("""
    var buttons = document.querySelectorAll('#test-id button')
    buttons[0].click()
""")
```

Improved approach:
```kotlin
js("""
    var buttons = Array.from(document.querySelectorAll('#test-id button'))
    var loginButton = buttons.find(btn => btn.textContent.trim() === 'Login')
    loginButton.click()
""")
```

**2. Query inputs by placeholder instead of type**

Current approach:
```kotlin
js("document.querySelector('#test-id input[type=\"text\"]')")
```

Improved approach:
```kotlin
js("document.querySelector('#test-id input[placeholder=\"Username\"]')")
```

### Future Improvements (When Building More Tests)

**1. Document the pattern**
- Create examples showing how to query by user-visible attributes
- Add comments explaining why we avoid position-based queries

**2. Consider test helper functions** (if Kotlin/JS limitations can be worked around)
- Helpers that abstract repetitive query patterns
- Would allow updating query strategy in one place

**3. Add accessibility attributes**
- Consider adding `aria-label` or `data-testid` attributes to critical elements
- Makes tests even more stable and improves accessibility

## Conclusion

### Current State: **Medium Quality**

Our tests are:
- ✅ Testing complete user flows
- ✅ Verifying behavior at API boundaries
- ✅ Using good dependency injection patterns
- ⚠️ Somewhat brittle to UI structure changes
- ⚠️ Vulnerable to button reordering

### Improvement Path

**Immediate actions**:
1. Update existing tests to query buttons by text instead of position
2. Query inputs by placeholder instead of type attribute
3. Document the pattern for future test writers

**Long-term**:
- The CoroutineScope injection pattern we've implemented is sound
- The FakeApiClient approach is correct
- The main improvements are in **how we query elements**, not **what we test**

### Trade-offs We Accept

**Acceptable**: Low-level event dispatch (necessary for Compose for Web)
**Acceptable**: Some DOM awareness (no Testing Library equivalent for Compose)
**Not acceptable**: Position-based button queries (easy to fix)
**Not acceptable**: Type-based input queries when placeholder available (easy to fix)

## Examples in This Codebase

**Good patterns** (keep doing this):
- `LoginPageRenderTest:loginPageEnterKeyInPasswordFieldTriggersAuthentication`
  - Uses `coroutineScope` injection ✅
  - Verifies API calls via FakeApiClient ✅
  - Simulates real user interaction ✅

**Could be improved**:
```kotlin
// Current
js("""
    var buttons = document.querySelectorAll('#login-button-click-test button')
    if (buttons && buttons.length > 0) {
        buttons[0].click()
    }
""")

// Better
js("""
    var buttons = Array.from(document.querySelectorAll('#login-button-click-test button'))
    var loginButton = buttons.find(btn => btn.textContent.trim() === 'Login')
    if (loginButton) loginButton.click()
""")
```

This small change makes tests resilient to button reordering without requiring any infrastructure changes.
