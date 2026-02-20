# State and Side Effect Management: Kotlin Compose for Web vs React Hooks

## Executive Summary

Both frameworks solve the same problem: **managing state and side effects in declarative UI components**. The key difference is **automatic vs. explicit dependency tracking**.

- **Compose for Web**: Compiler automatically tracks dependencies
- **React Hooks**: Developer manually declares dependencies

---

## Concept 1: Basic State Management

### Compose for Web
```kotlin
@Composable
fun Counter() {
    var count by remember { mutableStateOf(0) }

    Button(onClick = { count++ }) {
        Text("Clicked $count times")
    }
}
```

### React Hooks
```javascript
function Counter() {
    const [count, setCount] = useState(0);

    return (
        <button onClick={() => setCount(count + 1)}>
            Clicked {count} times
        </button>
    );
}
```

**Analysis:**
- **Simplicity**: Roughly equivalent
- **Compose advantage**: Direct mutation syntax (`count++`)
- **React advantage**: Immutable by default (prevents certain bugs)

---

## Concept 2: Side Effects (Running Code on Changes)

### Compose for Web
```kotlin
@Composable
fun Logger() {
    var count by remember { mutableStateOf(0) }

    // Automatically re-runs when count changes
    LaunchedEffect(count) {
        println("Count changed to: $count")
    }

    Button(onClick = { count++ }) {
        Text("Increment")
    }
}
```

### React Hooks
```javascript
function Logger() {
    const [count, setCount] = useState(0);

    // Must manually declare [count] dependency
    useEffect(() => {
        console.log("Count changed to:", count);
    }, [count]);

    return <button onClick={() => setCount(count + 1)}>Increment</button>;
}
```

**Analysis:**
- **Compose advantage**: Dependency is the key (`LaunchedEffect(count)`) — explicit and type-checked
- **React disadvantage**: Easy to forget `[count]` in dependency array
- **React disadvantage**: ESLint warnings needed to catch missing dependencies

---

## Concept 3: Multiple Dependencies

### Compose for Web
```kotlin
@Composable
fun SearchResults(query: String, pageSize: Int) {
    var results by remember { mutableStateOf<List<String>>(emptyList()) }

    // Automatically tracks both dependencies
    LaunchedEffect(query, pageSize) {
        results = searchApi(query, pageSize)
    }

    results.forEach { Text(it) }
}
```

### React Hooks
```javascript
function SearchResults({ query, pageSize }) {
    const [results, setResults] = useState([]);

    // Must remember to list all dependencies
    useEffect(() => {
        searchApi(query, pageSize).then(setResults);
    }, [query, pageSize]); // Easy to forget one

    return results.map(r => <div>{r}</div>);
}
```

**Analysis:**
- **Both**: Explicit about what triggers re-execution
- **Compose advantage**: Type-checked keys — compiler error if you pass wrong type
- **React disadvantage**: Easy to forget a dependency (runtime bug, not compile error)

---

## Concept 4: Cleanup (Cancellation/Disposal)

### Compose for Web
```kotlin
@Composable
fun UserProfile(userId: String) {
    var user by remember { mutableStateOf<User?>(null) }

    LaunchedEffect(userId) {
        val job = launch {
            user = fetchUser(userId)
        }
        // Automatic cleanup when userId changes or component unmounts
    }

    user?.let { Text("Hello, ${it.name}") }
}
```

### React Hooks
```javascript
function UserProfile({ userId }) {
    const [user, setUser] = useState(null);

    useEffect(() => {
        let cancelled = false;

        fetchUser(userId).then(data => {
            if (!cancelled) setUser(data);
        });

        // Must manually implement cleanup
        return () => {
            cancelled = true;
        };
    }, [userId]);

    return user && <div>Hello, {user.name}</div>;
}
```

**Analysis:**
- **Compose advantage**: Coroutines automatically cancelled when effect restarts
- **React disadvantage**: Manual cleanup required to avoid race conditions
- **React disadvantage**: More boilerplate for common pattern

---

## Concept 5: Derived State

### Compose for Web
```kotlin
@Composable
fun ShoppingCart(items: List<Item>) {
    // Automatically recalculates when items changes
    val total = items.sumOf { it.price }
    val tax = total * 0.08
    val grandTotal = total + tax

    Text("Total: $$grandTotal")
}
```

### React Hooks
```javascript
function ShoppingCart({ items }) {
    // Recalculates every render unless memoized
    const total = items.reduce((sum, item) => sum + item.price, 0);
    const tax = total * 0.08;
    const grandTotal = total + tax;

    return <div>Total: ${grandTotal}</div>;
}
```

**Better React with useMemo:**
```javascript
function ShoppingCart({ items }) {
    const total = useMemo(
        () => items.reduce((sum, item) => sum + item.price, 0),
        [items] // Must declare dependency
    );
    const tax = useMemo(() => total * 0.08, [total]);
    const grandTotal = useMemo(() => total + tax, [total, tax]);

    return <div>Total: ${grandTotal}</div>;
}
```

**Analysis:**
- **Compose advantage**: Automatic smart recomposition — only recalculates when dependencies change
- **React**: Either recalculates unnecessarily OR requires manual `useMemo` with dependency tracking
- **React disadvantage**: More decisions to make (optimize or not?), more code if optimizing

---

## Concept 6: Computed State with Side Effects

### Compose for Web
```kotlin
@Composable
fun SearchBox() {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<String>>(emptyList()) }
    val trimmedQuery = query.trim()

    // Only runs when trimmedQuery changes (automatic)
    LaunchedEffect(trimmedQuery) {
        if (trimmedQuery.isNotEmpty()) {
            results = searchApi(trimmedQuery)
        }
    }

    TextField(value = query, onValueChange = { query = it })
    results.forEach { Text(it) }
}
```

### React Hooks
```javascript
function SearchBox() {
    const [query, setQuery] = useState("");
    const [results, setResults] = useState([]);
    const trimmedQuery = query.trim();

    // Runs when trimmedQuery changes
    useEffect(() => {
        if (trimmedQuery) {
            searchApi(trimmedQuery).then(setResults);
        }
    }, [trimmedQuery]); // Must remember dependency

    return (
        <>
            <input value={query} onChange={(e) => setQuery(e.target.value)} />
            {results.map(r => <div>{r}</div>)}
        </>
    );
}
```

**Analysis:**
- **Compose advantage**: `trimmedQuery` is automatically tracked — computed value "just works" as dependency
- **React**: Works similarly BUT easy to forget dependencies or have stale closures
- **Roughly equivalent** when written correctly

---

## Concept 7: Rules and Constraints

### Compose for Web
```kotlin
@Composable
fun Example(condition: Boolean) {
    // ✓ ALLOWED: Composable calls can be conditional
    if (condition) {
        var state by remember { mutableStateOf(0) }
        Text("State: $state")
    }

    // ✓ ALLOWED: Can be in loops
    repeat(5) {
        Text("Item $it")
    }
}
```

### React Hooks
```javascript
function Example({ condition }) {
    // ✗ FORBIDDEN: Hooks must be at top level
    if (condition) {
        const [state, setState] = useState(0); // ERROR!
    }

    // ✗ FORBIDDEN: Hooks cannot be in loops
    for (let i = 0; i < 5; i++) {
        const [item, setItem] = useState(i); // ERROR!
    }

    // ✓ Must be like this:
    const [state, setState] = useState(0);

    return condition ? <div>State: {state}</div> : null;
}
```

**Analysis:**
- **Compose advantage**: More flexible — composable functions can be called conditionally or in loops
- **React constraint**: Rules of Hooks are strict to maintain call order consistency
- **React disadvantage**: More cognitive overhead, easier to make mistakes

---

## Concept 8: Stale Closures

### Compose for Web
```kotlin
@Composable
fun Timer() {
    var count by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            count++ // Always references current count
        }
    }

    Button(onClick = { println("Current count: $count") }) {
        Text("Count: $count")
    }
}
```

### React Hooks (Common Bug)
```javascript
function Timer() {
    const [count, setCount] = useState(0);

    useEffect(() => {
        const interval = setInterval(() => {
            setCount(count + 1); // BUG! Captures stale count
        }, 1000);

        return () => clearInterval(interval);
    }, []); // Empty deps means count is stale

    return <button onClick={() => console.log("Current count:", count)}>
        Count: {count}
    </button>;
}
```

### React Hooks (Fixed)
```javascript
function Timer() {
    const [count, setCount] = useState(0);

    useEffect(() => {
        const interval = setInterval(() => {
            setCount(c => c + 1); // Use updater function
        }, 1000);

        return () => clearInterval(interval);
    }, []); // Now safe

    return <button onClick={() => console.log("Current count:", count)}>
        Count: {count}
    </button>;
}
```

**Analysis:**
- **Compose advantage**: No stale closure issues — state is accessed directly
- **React disadvantage**: Common bug pattern requiring updater functions (`c => c + 1`)
- **React disadvantage**: Requires understanding closure semantics

---

## Comprehensive Example: All Concepts Together

**Scenario:** A search component with debouncing, loading state, error handling, and cleanup.

### Compose for Web
```kotlin
@Composable
fun UserSearch() {
    var searchQuery by remember { mutableStateOf("") }
    var debouncedQuery by remember { mutableStateOf("") }
    var users by remember { mutableStateOf<List<User>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Debounce the search query
    LaunchedEffect(searchQuery) {
        delay(500) // Wait 500ms
        debouncedQuery = searchQuery
    }

    // Fetch users when debounced query changes
    LaunchedEffect(debouncedQuery) {
        if (debouncedQuery.isEmpty()) {
            users = emptyList()
            return@LaunchedEffect
        }

        isLoading = true
        error = null

        try {
            users = fetchUsers(debouncedQuery)
        } catch (e: Exception) {
            error = e.message
        } finally {
            isLoading = false
        }
    }

    Column {
        TextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search users...") }
        )

        when {
            isLoading -> Text("Loading...")
            error != null -> Text("Error: $error", color = Color.Red)
            users.isEmpty() -> Text("No users found")
            else -> users.forEach { user ->
                Text(user.name)
            }
        }
    }
}
```

### React Hooks
```javascript
function UserSearch() {
    const [searchQuery, setSearchQuery] = useState("");
    const [debouncedQuery, setDebouncedQuery] = useState("");
    const [users, setUsers] = useState([]);
    const [isLoading, setIsLoading] = useState(false);
    const [error, setError] = useState(null);

    // Debounce the search query
    useEffect(() => {
        const timer = setTimeout(() => {
            setDebouncedQuery(searchQuery);
        }, 500);

        return () => clearTimeout(timer); // Cleanup
    }, [searchQuery]);

    // Fetch users when debounced query changes
    useEffect(() => {
        if (!debouncedQuery) {
            setUsers([]);
            return;
        }

        let cancelled = false; // Race condition protection

        setIsLoading(true);
        setError(null);

        fetchUsers(debouncedQuery)
            .then(data => {
                if (!cancelled) {
                    setUsers(data);
                    setIsLoading(false);
                }
            })
            .catch(err => {
                if (!cancelled) {
                    setError(err.message);
                    setIsLoading(false);
                }
            });

        return () => {
            cancelled = true; // Cleanup
        };
    }, [debouncedQuery]);

    return (
        <div>
            <input
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                placeholder="Search users..."
            />

            {isLoading && <div>Loading...</div>}
            {error && <div style={{color: 'red'}}>Error: {error}</div>}
            {!isLoading && !error && users.length === 0 && <div>No users found</div>}
            {users.map(user => <div key={user.id}>{user.name}</div>)}
        </div>
    );
}
```

**Side-by-Side Comparison:**

| Aspect | Compose for Web | React Hooks |
|--------|----------------|-------------|
| **Lines of code** | ~35 lines | ~50 lines |
| **Cleanup boilerplate** | None (automatic) | 2 manual cleanups required |
| **Race condition handling** | Automatic | Manual `cancelled` flag |
| **Dependency tracking** | Automatic via keys | Manual arrays `[searchQuery]`, `[debouncedQuery]` |
| **Error handling** | try/catch (idiomatic) | Promise .then/.catch chains |
| **State updates** | Direct mutation | Setter functions |

---

## Summary of Advantages

### Compose for Web Advantages

1. **Automatic dependency tracking** — Compiler tracks what each component reads
2. **No stale closures** — State is accessed directly, not captured
3. **Automatic cleanup** — Coroutines cancelled automatically
4. **Fewer rules** — Composables can be called conditionally or in loops
5. **Less boilerplate** — No manual cleanup, no race condition flags
6. **Direct mutation syntax** — More concise state updates
7. **Better tooling** — Compiler catches dependency issues at compile-time

### React Hooks Advantages

1. **Explicitness** — Dependency arrays make side effect triggers visible
2. **Mature ecosystem** — Vastly larger community, libraries, resources
3. **Fine-grained control** — Can optimize exactly when effects run
4. **No compiler magic** — Just JavaScript, easier to debug for some
5. **Platform ubiquity** — Runs everywhere JavaScript runs
6. **Better known** — More developers already understand it

---

## Complexity Comparison

### Mental Models Required

**Compose for Web:**
- Understand composable functions
- Understand `remember` and `mutableStateOf`
- Understand `LaunchedEffect` keys
- Understand coroutines (for async)

**React Hooks:**
- Understand closure semantics
- Understand dependency arrays
- Understand Rules of Hooks (top-level, order)
- Understand stale closure problem
- Understand cleanup functions
- Understand updater functions (`c => c + 1`)
- Understand race conditions in async effects

### Common Pitfalls

**Compose for Web:**
- Forgetting `remember` (recreating state on every recomposition)
- Incorrect `LaunchedEffect` keys

**React Hooks:**
- Missing dependencies in arrays
- Stale closures
- Forgetting cleanup functions
- Breaking Rules of Hooks (conditional calls)
- Race conditions in async effects
- Unnecessary re-renders without `useMemo`/`useCallback`

---

## Decision Factors

**Choose Compose for Web if:**
- Starting a new Kotlin project
- Value simplicity and automatic dependency tracking
- Want compiler-checked correctness
- Don't need React's ecosystem

**Choose React Hooks if:**
- Already in JavaScript/TypeScript ecosystem
- Need React's vast library ecosystem
- Team already knows React
- Need to run on platforms Kotlin doesn't target well

---

## Conclusion

**The core difference is philosophical:**

- **Compose for Web** chose to hide complexity through compiler intelligence, making common cases simple at the cost of some "magic"
- **React Hooks** chose explicitness and manual control, giving developers power at the cost of cognitive overhead

Both are valid engineering trade-offs. Compose for Web is **objectively simpler** for common use cases. React Hooks provides **objectively more control** and has a **much larger ecosystem**.

The "best" choice depends on your team's priorities: simplicity vs. control, Kotlin vs. JavaScript, compiler help vs. explicit code.
