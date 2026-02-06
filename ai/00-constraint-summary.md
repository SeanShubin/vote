# Constraint Summary: Complete Execution Blueprint

**Purpose:** This document consolidates all constraint decisions. It is the definitive reference for execution.

**Status:** ✅ All constraints finalized and approved.

---

## The Mission

Transform three separate projects (condorcet-backend, condorcet-frontend, condorcet-deploy) into a single Kotlin Multiplatform application to validate a novel testing methodology where:
- Tests read like human browser interactions (BDD-style)
- Tests exercise actual ViewModels (not HTTP, not Selenium)
- Dual database projections (MySQL + DynamoDB) prove correctness
- Event log is behavioral specification
- AI has full implementation autonomy within constraints

---

## Project Structure

### Module Organization (Q1)
**Decision:** Flat modules
```
vote/
├── domain/          # Pure domain logic (commonMain)
├── contract/        # API DTOs (commonMain)
├── backend/         # All backend code (jvmMain)
├── frontend/        # All frontend code (jsMain)
├── deploy/          # AWS CDK (jvmMain)
├── local/           # Local dev runner (jvmMain)
└── integration/     # Single regression test (jvmTest)
```

### Package Naming (Q2)
**Decision:** `com.seanshubin.vote.*`

### Backend Package Structure (Q3)
**Decision:** Flatten 15+ Maven modules into packages
```
backend/src/main/kotlin/com/seanshubin/vote/backend/
├── server/          # Jetty HTTP server
├── service/         # Business logic
├── database/        # Repository implementations
│   ├── mysql/       # MySQL projection
│   ├── dynamo/      # DynamoDB projection
│   └── eventlog/    # Event log (DynamoDB table)
├── auth/            # JWT + crypto
├── email/           # Email sending
├── http/            # HTTP utilities
├── json/            # JSON utilities (kotlinx.serialization)
└── dependencies/    # Composition roots (Integrations/Bootstrap/Application)
```

---

## Build System

### Maven to Gradle (Q4)
**Decision:** AI converts all pom.xml → build.gradle.kts automatically

### Build Tools
- **Gradle:** 8.10+ (Kotlin DSL)
- **Java:** 21 (LTS)

---

## Data Architecture

### Event Log (Q6)
**Decision:** DynamoDB table from day 1
- Events ARE the behavior specification
- Table: PK=eventId, SK=timestamp, payload=JSON
- Append-only writes
- Both projections rebuild from events

### Dual Projections (Q5, Q7)
**Decision:** Both H2/MySQL and DynamoDB from day 1

**MySQL/H2 Projection:**
- Reference implementation (straightforward relational)
- H2 in-memory for tests
- MySQL for local dev (optional)
- Admin queries can be slow

**DynamoDB Projection:**
- Optimized implementation (single-table design)
- DynamoDB Local for tests/dev
- Fast user operations (<100ms target)
- Admin queries can scan (no performance req)

**Correctness Proof:**
```kotlin
// After each event, verify projections match
eventLog.append(event)
mysqlProjection.sync()
dynamoProjection.sync()
assertEquals(mysqlRepo.state, dynamoRepo.state) // Proof!
```

### DynamoDB Access Patterns (Q-Extra-1)
**Decision:** Design AFTER MySQL works
- Prove dual-projection concept with simple relational model first
- Observe actual query patterns
- Then optimize single-table DynamoDB schema

### Test Database (Q5)
**Decision:** DynamoDB Local + H2 (both via Testcontainers/embedded)

---

## Dependency Injection

### Pattern (Q13)
**Decision:** Integrations/Bootstrap/Application stages

**Structure:**
```kotlin
// Integrations - I/O boundaries
interface Integrations {
    val clock: Clock
    val eventLog: EventLog
    val projectionType: ProjectionType // MySQL, DynamoDB, or Both
}

object ProductionIntegrations : Integrations { ... }
object TestIntegrations : Integrations {
    fun verifyEquivalence() { /* compare projections */ }
}

// Bootstrap - Config loading
class BootstrapDependencies(args: Array<String>, integrations: Integrations) {
    val configuration: Configuration = loadConfig(...)
    val runner: Runnable = ApplicationDependencies.fromConfiguration(...)
}

// Application - Business wiring
class ApplicationDependencies(integrations: Integrations, config: Configuration) {
    private val userRepo = MySqlUserRepository(...) // or DynamoDB, or both
    private val service = ServiceImpl(...)
    val runner: Runnable = ServerRunner(...)
}
```

---

## Frontend

### Technology (Q8)
**Decision:** Compose for Web (Kotlin UI, multiplatform)

### Timeline (Q9)
**Decision:** After backend test passes

**Phasing:**
- **Milestone 1:** Backend + events + dual projections, Service-level test
- **Milestone 2:** Add frontend, elevate to ViewModel-level test

### Test DSL (Q-Extra-2)
**Decision:** Design upfront before frontend implementation

**Specification (to be expanded):**
```kotlin
interface TestApplication {
    val user: TestUser
    val currentPage: Page
    val integrations: TestIntegrations
}

interface TestUser {
    fun inputLabeled(label: String): TestInput
    fun pressButtonLabeled(label: String)
    val navigateTo: Navigation
}

interface TestInput {
    fun typeText(text: String)
    fun clear()
}

// Usage in test:
app.user.navigateTo.registerPage()
app.user.inputLabeled("email").typeText("foo@bar.com")
app.user.pressButtonLabeled("Register")
assertTrue(app.currentPage is DashboardPage)
app.integrations.verifyEquivalence()
```

---

## Testing Strategy

### Single Regression Test (Q10)
**Decision:** Complete user journey (happy path)

**Test covers:**
1. Register admin user
2. Register voter users (2)
3. Admin creates election
4. Admin sets candidates (3)
5. Admin sets eligible voters
6. Admin launches election
7. Voters cast ballots (ranked choice)
8. Admin finalizes election
9. View tally results
10. Admin deletes election
11. Admin deletes users
12. Verify system empty (both projections)

**Verification:** After each step, `integrations.verifyEquivalence()`

### Test Execution Level (Q11)
**Decision:** ViewModel/UI level (ambitious goal)

**Intermediate:** Service interface level for Milestone 1 (backend only)
**Target:** ViewModel level for Milestone 2 (with frontend)

Tests read like human browser interactions, drive ViewModels directly, no HTTP/Selenium.

### Existing Tests (Q17)
**Decision:** Discard completely
- Use current regression test as inspiration only
- Testing methodology is fundamentally different
- Old tests verify implementation; new test verifies behavior

---

## Migration Strategy

### Code Refactoring (Q14)
**Decision:** Refactor during migration
- Apply Integrations/Bootstrap/Application pattern
- Extract event definitions
- Ensure repositories behind interfaces
- Fix coding standard violations (anonymous code, etc.)
- Switch Jackson → kotlinx.serialization

### AI Autonomy (Q18)
**Decision:** Batches of related changes with summaries

**Examples:**
- "Migrate domain module" (10-15 files, show summary)
- "Set up event log + projections" (architectural, show structure)
- "Implement MySQL repositories" (mechanical, show summary)

**Process:**
1. AI executes batch
2. Shows summary of changes
3. User approves or requests adjustments
4. Iterate

---

## Dependencies

### Version Strategy (Q16)
**Decision:** Upgrade to latest STABLE (not bleeding edge)

### Core Versions
```kotlin
kotlin = "2.0.21"                    // Latest stable multiplatform
compose-multiplatform = "1.7.1"      // Latest stable Compose for Web
kotlinx-serialization = "1.7.3"      // Replaces Jackson
kotlinx-coroutines = "1.9.0"
gradle = "8.10"
java = "21"                          // LTS
```

### Infrastructure Versions
```kotlin
jetty = "11.0.24"                    // Latest 11.x (not 12.x)
mysql-connector = "8.0.33"
h2 = "2.2.224"
aws-sdk-kotlin = "1.3.x"             // Kotlin SDK
testcontainers = "1.20.4"
junit = "5.11.3"
```

### Key Change
**Jackson → kotlinx.serialization** for multiplatform event serialization

---

## Development Workflow

### Local Execution (Q12)
**Decision:** Single command `./gradlew run`

Starts:
- DynamoDB Local (Docker/Testcontainers)
- H2 in-memory database
- Backend server on :8080
- Frontend dev server on :3000 (proxies API to :8080)

### AWS Deployment (Q15)
**Decision:** Defer entirely

**Phasing:**
1. Milestone 1: Backend working locally
2. Milestone 2: Frontend + ViewModel tests working locally
3. Milestone 3: Deploy to AWS (ECS Fargate + real DynamoDB)

---

## Execution Milestones

### Milestone 1: Backend + Dual Projections
**Goal:** Backend works locally with event log and dual projections

**Deliverables:**
- ✅ Gradle multiplatform project builds
- ✅ Domain models in `domain/` (commonMain)
- ✅ Event log in DynamoDB Local
- ✅ MySQL projection (H2 in-memory)
- ✅ DynamoDB projection (DynamoDB Local)
- ✅ Service implementation
- ✅ Jetty HTTP server
- ✅ Integrations/Bootstrap/Application DI
- ✅ Single regression test at Service level
- ✅ Test verifies projections match after each operation
- ✅ `./gradlew run` starts local app
- ✅ `./gradlew test` passes

### Milestone 2: Frontend + ViewModel Tests
**Goal:** Frontend built with Compose for Web, test elevated to ViewModel level

**Deliverables:**
- ✅ Test DSL specification document
- ✅ Compose for Web frontend
- ✅ ViewModels with dependency injection
- ✅ UI components testable without browser
- ✅ Regression test rewritten to use ViewModel DSL
- ✅ Tests read like human browser interactions
- ✅ `app.user.inputLabeled("email").typeText(...)`
- ✅ `app.user.pressButtonLabeled("Register")`
- ✅ Full feature parity with existing app

### Milestone 3: AWS Deployment (Future)
**Goal:** Deploy to AWS with production infrastructure

**Deferred until Milestones 1 & 2 complete.**

---

## Success Criteria

### Milestone 1 Success
- [ ] `./gradlew build` succeeds
- [ ] `./gradlew run` starts app locally
- [ ] `./gradlew test` passes
- [ ] Test covers full user journey (12 steps)
- [ ] After each step, both projections match
- [ ] Can reset to empty state (delete all data)
- [ ] No HTTP mocking, no database mocking
- [ ] Test runs fast (<30 seconds)

### Milestone 2 Success
- [ ] All Milestone 1 criteria met
- [ ] Frontend renders in browser at localhost:3000
- [ ] Test exercises ViewModels directly
- [ ] Test reads like BDD specification
- [ ] No Selenium, no browser automation
- [ ] Feature parity with existing React app

---

## Non-Goals (Explicitly Out of Scope for Now)

- ❌ AWS deployment (Milestone 3, future)
- ❌ Real MySQL in tests (H2 sufficient for Milestone 1)
- ❌ Performance optimization (prove correctness first)
- ❌ Error path testing (happy path only initially)
- ❌ Frontend styling/design (functional UI only)
- ❌ CI/CD pipeline (local execution only)
- ❌ Monitoring/observability (future)
- ❌ Backup/restore (future)

---

## Ready to Execute

All constraints defined. Next steps:

1. **AI creates initial project structure** (Gradle multiplatform setup)
2. **AI migrates domain models** (batch 1)
3. **AI sets up event log + projections** (batch 2)
4. **AI implements repositories** (batch 3)
5. **AI implements service layer** (batch 4)
6. **AI implements HTTP server** (batch 5)
7. **AI writes regression test** (batch 6)
8. **Verify test passes** (Milestone 1 complete)

**User approval required before execution begins.**

Are you ready to start? If yes, I'll begin with Batch 1: Project setup and initial structure.
