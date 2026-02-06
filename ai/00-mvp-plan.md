# MVP Plan: Get Something Working Fast

## Goal
Get a minimal working system as quickly as possible to validate the architecture, then iterate.

## Simplified First Target

**What we're building:**
- Shared domain models (User, Election) in Kotlin Multiplatform
- Backend REST API with 3-4 core endpoints
- In-memory database (no persistence initially)
- Simple integration test
- No frontend yet (test with curl/Postman)
- No AWS deployment yet (local only)
- No event sourcing yet (add later)

**Why this approach:**
- Validates Gradle multiplatform setup
- Proves dependency injection pattern works
- Tests shared domain in both JVM (backend) and potentially JS (future frontend)
- Gets feedback loop working fast
- Low risk, high learning

## MVP Scope (Minimal Features)

### Endpoints (4 total)
1. `POST /register` - Create user
2. `POST /authenticate` - Login, get JWT token
3. `GET /users` - List users (requires auth)
4. `DELETE /users/{userName}` - Delete user (requires auth)

**Rationale:** This tests:
- Domain models (User)
- CRUD operations
- Authentication/authorization
- JWT tokens
- Repository pattern
- Dependency injection

### Domain Models (Minimal)
```kotlin
// shared/domain/commonMain
data class User(
    val userName: String,
    val email: String,
    val hash: String,  // password hash
    val salt: String,
    val role: Role
)

enum class Role {
    ADMIN, OBSERVER, VOTER
}

data class Tokens(
    val accessToken: String,
    val refreshToken: String
)
```

### Repository Interface
```kotlin
// shared/domain/commonMain
interface UserRepository {
    suspend fun findByUserName(userName: String): User?
    suspend fun findByEmail(email: String): User?
    suspend fun save(user: User)
    suspend fun delete(userName: String)
    suspend fun listAll(): List<User>
}
```

### In-Memory Implementation
```kotlin
// backend/jvmMain
class InMemoryUserRepository : UserRepository {
    private val users = mutableMapOf<String, User>()

    override suspend fun findByUserName(userName: String): User? =
        users[userName]

    override suspend fun save(user: User) {
        users[user.userName] = user
    }

    // ... etc
}
```

## Questions to Answer NOW

### Q1: Gradle Setup - Confirmed?
**Proposed:** Use Gradle Kotlin DSL with Kotlin Multiplatform Plugin

**Your decision:** ✅ Yes / ⚠️ Need to discuss

---

### Q2: Module Structure - Start Simple?
**Proposed initial structure:**
```
vote/
├── shared/          # Kotlin Multiplatform
│   └── src/
│       └── commonMain/
│           └── domain/    # User, Role, Tokens, UserRepository
├── backend/         # JVM only
│   └── src/
│       └── main/kotlin/
│           ├── repository/    # InMemoryUserRepository
│           ├── service/       # UserService
│           ├── http/          # Jetty + routes
│           ├── auth/          # JWT, crypto
│           └── dependencies/  # Composition roots
└── test/            # Integration tests
    └── src/
        └── test/kotlin/
            └── integration/
```

**Your decision:** ✅ Yes / ⚠️ Adjust structure

---

### Q3: Authentication - Start Simple?
**Proposed:**
- JWT tokens (Auth0 java-jwt library, already in current backend)
- Password hashing with BCrypt
- No email confirmation (just register → immediate login)
- No refresh tokens initially (just access tokens, add refresh later)

**Your decision:** ✅ Yes / ⚠️ Different approach

---

### Q4: Testing - Simple Integration Test?
**Proposed test:**
```kotlin
@Test
fun `user registration and authentication flow`() {
    val app = TestApplication() // starts in-memory backend

    // Register user
    val registerResponse = app.post("/register",
        RegisterRequest("alice", "alice@test.com", "password123"))
    assertEquals(200, registerResponse.status)
    val tokens = registerResponse.body<TokenResponse>()

    // List users (requires auth)
    val usersResponse = app.get("/users",
        headers = mapOf("Authorization" to "Bearer ${tokens.accessToken}"))
    val users = usersResponse.body<List<UserSummary>>()
    assertEquals(1, users.size)
    assertEquals("alice", users[0].userName)

    // Delete user
    val deleteResponse = app.delete("/users/alice",
        headers = mapOf("Authorization" to "Bearer ${tokens.accessToken}"))
    assertEquals(204, deleteResponse.status)

    // Verify deleted
    val emptyResponse = app.get("/users",
        headers = mapOf("Authorization" to "Bearer ${tokens.accessToken}"))
    assertEquals(0, emptyResponse.body<List<UserSummary>>().size)
}
```

**Your decision:** ✅ Yes / ⚠️ Different approach

---

### Q5: Skip These Initially?
**Proposed to defer:**
- ❌ Frontend (add in next phase)
- ❌ DynamoDB (add in next phase)
- ❌ Event sourcing (add in next phase)
- ❌ MySQL (add in next phase)
- ❌ Email sending (add in next phase)
- ❌ Elections/Voting domain (users only for MVP)
- ❌ AWS deployment (add in next phase)

**Rationale:** Get the architecture working first with minimal scope, then add features incrementally.

**Your decision:** ✅ Yes / ⚠️ Want to include something from this list

---

## Timeline for MVP

**Optimistic:** 2-3 days
**Realistic:** 1 week

### Day 1: Project Setup
- [ ] Create Gradle multiplatform project
- [ ] Set up shared/domain module (commonMain)
- [ ] Define User, Role, UserRepository
- [ ] Verify: `./gradlew :shared:build` succeeds

### Day 2: Backend Implementation
- [ ] Create backend module (JVM)
- [ ] Implement InMemoryUserRepository
- [ ] Implement UserService (register, auth, list, delete)
- [ ] Implement JWT token generation/validation
- [ ] Implement password hashing
- [ ] Verify: Unit tests pass

### Day 3: HTTP Layer
- [ ] Set up Jetty server
- [ ] Create HTTP routes (POST /register, POST /authenticate, GET /users, DELETE /users/:name)
- [ ] Wire dependencies (Integrations/Bootstrap/Application pattern)
- [ ] Verify: Can start server, hit endpoints with curl

### Day 4: Integration Test
- [ ] Create test module
- [ ] Write integration test (full flow)
- [ ] Verify: `./gradlew test` passes
- [ ] Fix any issues

### Day 5: Polish & Documentation
- [ ] Add error handling
- [ ] Add logging
- [ ] Write README (how to run, how to test)
- [ ] Demo the working system

## Success Criteria

✅ `./gradlew build` succeeds
✅ `./gradlew :backend:run` starts server on localhost:8080
✅ Can register user: `curl -X POST http://localhost:8080/register -d '{"userName":"alice","email":"alice@test.com","password":"pass"}'`
✅ Can login: `curl -X POST http://localhost:8080/authenticate -d '{"nameOrEmail":"alice","password":"pass"}'`
✅ Can list users with token: `curl http://localhost:8080/users -H "Authorization: Bearer <token>"`
✅ Can delete user: `curl -X DELETE http://localhost:8080/users/alice -H "Authorization: Bearer <token>"`
✅ Integration test passes: `./gradlew test`

## What We Learn

By the end of MVP:
- ✅ Gradle multiplatform setup works
- ✅ Shared domain models work
- ✅ Dependency injection pattern works
- ✅ JWT authentication works
- ✅ HTTP server works
- ✅ Repository pattern works
- ✅ Integration testing works

**Then we can confidently add:**
- Elections & voting domain
- DynamoDB support
- Event sourcing
- Frontend (Compose for Web)
- AWS deployment

## Your Decisions

Please review Q1-Q5 above and let me know:
1. Any you want to change?
2. Any concerns about this approach?
3. Ready to start Day 1 (project setup)?

I can help create the initial Gradle build files and project structure once you confirm the approach.
