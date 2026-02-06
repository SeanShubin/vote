# Technical Decisions

This document captures key technical choices that need to be made or confirmed. Each decision includes context, options, trade-offs, and recommendations.

## 1. Event Log Technology

**Decision Required**: What technology should store the immutable event log (source of truth)?

### Options

#### A. File-Based Append-Only Log
**Pros:**
- Simplest implementation
- No external dependencies for local development
- Easy to inspect (just read files)
- Natural fit with event sourcing patterns

**Cons:**
- Requires shared file system in AWS (EFS)
- File locking complexity for concurrent writes
- No built-in replication/durability guarantees
- Backup/restore requires custom implementation

**Local**: ✅ Excellent (just write to disk)
**AWS**: ⚠️ Requires EFS or S3 (eventual consistency issues)

#### B. DynamoDB with Streams
**Pros:**
- AWS-native, highly durable
- Automatic replication across AZs
- DynamoDB Streams for event processing
- No infrastructure management
- Scales automatically

**Cons:**
- Costs money even when idle (provisioned capacity or on-demand pricing)
- Local development requires DynamoDB Local or mocking
- Not portable outside AWS
- Event ordering per partition key only (need careful design)

**Local**: ⚠️ Requires DynamoDB Local Docker container
**AWS**: ✅ Excellent fit

#### C. Amazon Kinesis Data Streams
**Pros:**
- Purpose-built for event streaming
- Strong ordering guarantees per shard
- Retained for 7-365 days (configurable)
- Multiple consumers can read independently
- Real-time processing

**Cons:**
- More expensive than DynamoDB
- Requires shard management (or pay more for on-demand)
- Local development requires LocalStack or mocking
- Overkill for low-throughput voting app

**Local**: ⚠️ Requires LocalStack or mocking
**AWS**: ✅ Great for high throughput, expensive for low throughput

#### D. S3 Event Storage with Notifications
**Pros:**
- Extremely durable (11 9's)
- Cheapest storage cost
- S3 Event Notifications can trigger processing
- Easy backup (just copy S3 bucket)
- S3 Select or Athena for queries

**Cons:**
- Not designed for high-frequency writes
- Eventual consistency (recently improved but still consideration)
- No ordering guarantees
- Lambda limits for processing (15 min timeout)

**Local**: ✅ MinIO or LocalStack
**AWS**: ⚠️ Works but not ideal for real-time sync

### Recommendation
**DynamoDB with Streams** for MVP:
- Best balance of durability, AWS integration, and simplicity
- DynamoDB Local works fine for development
- Streams enable reactive projections
- Can migrate to Kinesis later if throughput demands it

**Table Design:**
```kotlin
Table: CondorcetEvents
PK: eventId (UUID)
SK: timestamp (ISO 8601 string for sorting)
Attributes:
- eventType: String (UserRegistered, ElectionCreated, etc.)
- aggregateId: String (userName, electionName, etc.)
- payload: String (JSON serialized event)
- version: Int (event schema version)
- userId: String? (who performed the action)

GSI: aggregateId-timestamp-index
- PK: aggregateId
- SK: timestamp
(Allows querying all events for a specific user or election)
```

---

## 2. DynamoDB Table Design

**Decision Required**: Single-table design or multi-table design?

### Options

#### A. Single-Table Design
One table with PK/SK overloading, GSIs for access patterns.

**Pros:**
- Fewer tables to manage
- Atomic transactions across entity types
- Follows DynamoDB best practices
- Lower cost (fewer tables)

**Cons:**
- More complex queries (need to understand PK/SK patterns)
- Harder to reason about for developers unfamiliar with single-table
- Refactoring access patterns can be disruptive

#### B. Multi-Table Design
Separate table for each entity (User, Election, Ballot, etc.).

**Pros:**
- Easier to understand (table-per-entity matches mental model)
- Simpler queries
- Easier to add new entities
- Better separation of concerns

**Cons:**
- No cross-table transactions (DynamoDB TransactWriteItems works across tables, but limited)
- Higher cost (more tables = more provisioned capacity or on-demand charges)
- More tables to manage

### Recommendation
**Multi-Table Design** for MVP:
- Easier for developers to understand and maintain
- Access patterns are relatively simple (no complex joins needed)
- Can optimize later with single-table if costs become an issue

**Tables:**
- `CondorcetUsers` (PK: userName, GSI: email)
- `CondorcetElections` (PK: electionName, GSI: ownerName)
- `CondorcetCandidates` (PK: electionName, SK: candidateName)
- `CondorcetBallots` (PK: voterName-electionName, GSI: electionName)
- `CondorcetEligibility` (PK: electionName-voterName, GSI: electionName)
- `CondorcetEvents` (PK: eventId, SK: timestamp, GSI: aggregateId-timestamp)

---

## 3. Build System: Gradle vs. Maven

**Current State**: Backend uses Maven, Deploy uses Maven, Frontend uses npm.

**Decision Required**: Migrate to Gradle or stick with Maven?

### Options

#### A. Gradle (Kotlin DSL)
**Pros:**
- Kotlin DSL natural fit for Kotlin Multiplatform
- Better IDE support for Kotlin projects
- More flexible and powerful than Maven
- Built-in multiplatform support
- Compose Multiplatform officially supports Gradle

**Cons:**
- Migration effort from Maven
- Learning curve if team is Maven-centric
- Build times can be slower (though caching helps)

#### B. Maven
**Pros:**
- Already in use for backend and deploy
- Team familiarity
- Simpler model (XML, convention-based)

**Cons:**
- Kotlin Multiplatform support is second-class (possible but awkward)
- Compose for Web requires Gradle
- Harder to customize build logic

### Recommendation
**Gradle (Kotlin DSL)**: Compose for Web requires Gradle, so this is effectively decided. Migrate Maven projects to Gradle.

---

## 4. Local Database: Embedded H2 vs. Testcontainers MySQL

**Decision Required**: What database should local development use?

### Options

#### A. H2 Embedded Database
**Pros:**
- Zero setup (just include dependency)
- Fast startup
- In-memory mode for tests
- File-based mode for persistent local dev

**Cons:**
- SQL dialect differences from MySQL (can cause bugs)
- Not a "real" database (prod is MySQL/DynamoDB)
- May hide production issues

#### B. Testcontainers MySQL
**Pros:**
- Exact same database as production (MySQL)
- No dialect issues
- Realistic integration testing
- Can start with Docker Compose as well

**Cons:**
- Requires Docker
- Slower startup (container lifecycle)
- More complex setup

#### C. DynamoDB Local + Optional MySQL
**Pros:**
- Matches production more closely (DynamoDB)
- Can run MySQL in Docker if needed
- Flexible (use what you need)

**Cons:**
- Two databases to manage locally
- DynamoDB Local has quirks (not 100% identical to real DynamoDB)

### Recommendation
**DynamoDB Local + Optional H2**:
- Primary: DynamoDB Local (matches AWS production)
- Optional: H2 if relational queries needed for development convenience
- Tests use in-memory H2 or DynamoDB Local (depending on what's being tested)

**Rationale**: If DynamoDB is the production database, local dev should use DynamoDB Local to catch issues early.

---

## 5. AWS Compute: ECS Fargate vs. Lambda

**Decision Required**: How should the backend run in AWS?

### Options

#### A. ECS Fargate
**Pros:**
- Jetty server runs as-is (minimal code changes)
- No cold starts
- Predictable latency
- Easier debugging (logs, exec into container)
- WebSocket support (if needed later)

**Cons:**
- Always-on costs (even when idle)
- Slightly more complex than Lambda (but not much with Fargate)
- Need to manage container lifecycle (health checks, etc.)

#### B. AWS Lambda with API Gateway
**Pros:**
- True pay-per-request (no idle costs)
- Scales to zero
- AWS handles all infrastructure
- No container management

**Cons:**
- Cold starts (Kotlin/JVM startup can be 5-10 seconds)
- 15-minute execution limit (not an issue for voting app)
- Requires Lambda-specific entrypoint
- SnapStart may help cold starts but adds complexity

### Recommendation
**ECS Fargate** for MVP:
- Simpler migration path (Jetty runs unmodified)
- Predictable performance (no cold starts)
- WebSocket support if realtime features needed later
- Can always migrate to Lambda if costs become an issue

**Costs Estimate:**
- Fargate: ~$30-50/month for 0.5 vCPU, 1GB RAM, always-on
- Lambda: $0 if low usage, $5-20/month for moderate usage
- For a voting app (likely low traffic), costs are negligible either way

---

## 6. Frontend State Management

**Decision Required**: What pattern for managing client-side state in Compose for Web?

### Options

#### A. Simple StateFlow + ViewModels
```kotlin
class ElectionViewModel(private val api: ApiClient) {
    private val _elections = MutableStateFlow<List<Election>>(emptyList())
    val elections: StateFlow<List<Election>> = _elections.asStateFlow()

    suspend fun loadElections() {
        _elections.value = api.listElections()
    }
}
```

**Pros:**
- Simple, idiomatic Kotlin
- Built into Kotlin (kotlinx.coroutines)
- Compose reacts to StateFlow automatically
- Easy to understand and test

**Cons:**
- No middleware (like Redux Saga)
- Manual coordination of async operations
- No time-travel debugging

#### B. Redux-like Library (e.g., Redux-Kotlin)
**Pros:**
- Familiar to developers coming from React/Redux
- Structured state management
- Middleware support

**Cons:**
- Extra dependency
- More boilerplate
- Might be overkill for this app

#### C. MVI (Model-View-Intent) Architecture
**Pros:**
- Unidirectional data flow
- Testable
- Clear separation of concerns

**Cons:**
- More abstraction layers
- Steeper learning curve

### Recommendation
**Simple StateFlow + ViewModels** for MVP:
- Compose for Web is new, keep state management simple
- StateFlow is idiomatic Kotlin
- Can refactor to MVI or Redux pattern later if complexity grows

---

## 7. API Design: REST vs. GraphQL

**Current State**: Backend uses REST-like HTTP endpoints.

**Decision Required**: Preserve REST or migrate to GraphQL?

### Options

#### A. REST (Preserve Current API)
**Pros:**
- Already implemented and working
- Simple, well-understood
- No additional dependencies
- Easy to test with curl/Postman

**Cons:**
- Over-fetching (get full election when only need name)
- Multiple requests for related data (election + candidates + ballots)
- No schema introspection

#### B. GraphQL
**Pros:**
- Clients request exactly what they need
- Single endpoint for all queries
- Strong typing (schema-first)
- Built-in introspection and docs

**Cons:**
- Learning curve
- Backend requires GraphQL server (kotlin-graphql, etc.)
- Frontend requires GraphQL client
- Caching is harder
- Might be overkill for simple CRUD

### Recommendation
**REST (Preserve Current API)** for MVP:
- Backend API already works and covers all use cases
- Frontend can use shared DTOs (type-safe without GraphQL)
- GraphQL adds complexity without clear benefit for this app size
- Can always add GraphQL later if needed

---

## 8. Dependency Injection Framework vs. Manual

**Current State**: Backend uses manual composition (dependencies module).

**Decision Required**: Continue manual DI or adopt a framework (Koin, Kodein)?

### Options

#### A. Manual Composition (Current Approach)
**Pros:**
- Follows your coding standards
- No framework dependency
- Complete control
- Compile-time safety
- Explicit wiring (no magic)

**Cons:**
- More boilerplate
- Larger composition root classes

#### B. Koin (Kotlin DI Framework)
**Pros:**
- Kotlin-idiomatic
- Multiplatform support
- Less boilerplate
- DSL for module definition

**Cons:**
- Runtime dependency resolution (can fail at runtime)
- Another dependency
- "Magic" lookup (less explicit than manual)

### Recommendation
**Manual Composition** (Preserve Current Approach):
- Already follows your coding standards (Integrations/Bootstrap/Application stages)
- Compile-time safety prevents DI errors
- Explicit wiring aids understanding
- Koin adds minimal value for this project size

---

## 9. Authentication Token Storage (Frontend)

**Decision Required**: Where should the frontend store JWT tokens?

### Options

#### A. LocalStorage
**Pros:**
- Simple API
- Persists across browser restarts
- Easy to access from JavaScript/Kotlin

**Cons:**
- Vulnerable to XSS attacks
- Accessible by any script on the page

#### B. SessionStorage
**Pros:**
- Same as LocalStorage but cleared when tab closes
- Still vulnerable to XSS

**Cons:**
- Lost on tab close (annoying UX)

#### C. HttpOnly Cookies
**Pros:**
- Not accessible to JavaScript (immune to XSS)
- Automatically sent with requests
- Secure flag prevents transmission over HTTP

**Cons:**
- Requires backend to set cookies
- CSRF protection needed (though CORS helps)
- Slightly more complex

#### D. Memory Only (No Persistence)
**Pros:**
- Most secure (lost on page reload)
- Forces re-authentication frequently

**Cons:**
- Terrible UX (must login every page reload)

### Recommendation
**LocalStorage** for MVP, with plan to migrate to HttpOnly Cookies:
- Simplest implementation for MVP
- Tokens have short expiry (mitigates risk)
- Can add HttpOnly cookie support later (backend already supports refreshToken flow)

**Security Mitigation:**
- Short access token expiry (15 minutes)
- Refresh tokens with longer expiry
- Implement CORS properly
- Consider Content Security Policy (CSP) headers

---

## 10. Deployment Automation: CDK vs. Terraform

**Current State**: Uses AWS CDK (Kotlin).

**Decision Required**: Continue with CDK or switch to Terraform?

### Options

#### A. AWS CDK (Current)
**Pros:**
- Already in use
- Kotlin DSL (consistent with app code)
- AWS-native (first-class support)
- Synthesizes to CloudFormation

**Cons:**
- AWS-only (not portable)
- CloudFormation can be slow
- Debugging can be tricky

#### B. Terraform
**Pros:**
- Multi-cloud (portable)
- Mature ecosystem
- Better state management
- HCL is widely known

**Cons:**
- Learning curve
- Yet another language (HCL)
- Migration effort

### Recommendation
**AWS CDK (Preserve Current Approach)**:
- Already working
- Kotlin DSL is consistent with application code
- AWS-only is fine (no multi-cloud requirement)
- CloudFormation slowness is acceptable for infrequent deploys

---

## Summary of Recommendations

| Decision | Recommendation | Priority |
|----------|---------------|----------|
| Event Log | DynamoDB with Streams | High |
| DynamoDB Design | Multi-Table | High |
| Build System | Gradle (Kotlin DSL) | High |
| Local Database | DynamoDB Local + Optional H2 | Medium |
| AWS Compute | ECS Fargate | High |
| Frontend State | StateFlow + ViewModels | Medium |
| API Design | REST (preserve current) | High |
| Dependency Injection | Manual Composition | High |
| Token Storage | LocalStorage (MVP) → HttpOnly Cookies (future) | Low |
| Deployment | AWS CDK (Kotlin) | High |

All high-priority decisions should be confirmed before starting implementation.
