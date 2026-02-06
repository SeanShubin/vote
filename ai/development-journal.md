# Development Journal: Building Vote with AI

This document chronicles the development of the Vote project - a migration of three separate Condorcet voting system projects into a single Kotlin Multiplatform application, built collaboratively with AI assistance.

## Session 1: Initial Planning (2026-02-05)

### Context
Starting with three existing projects:
- `condorcet-backend`: Kotlin/Maven backend with Jetty, MySQL, event sourcing
- `condorcet-frontend`: React frontend with Redux
- `condorcet-deploy`: AWS CDK deployment in Kotlin

Goal: Merge into single Kotlin Multiplatform project with Compose for Web, dual database support (MySQL + DynamoDB), event sourcing, and AWS deployment capability.

### Planning Phase

**Created comprehensive planning documents:**
1. `01-overview.md` - Project goals, constraints, requirements
2. `02-current-state.md` - Analysis of existing 3 projects
3. `03-target-architecture.md` - Vision for merged system
4. `04-technical-decisions.md` - Technology choices with trade-offs
5. `05-migration-plan.md` - 13-week phased implementation plan

**Key insight from user:** "I want to be aggressive on this one... I want to lean into already having a 99% feature complete version of the app. I want to lean into AI capability of making changes far beyond the capability of a human as long as they are sufficiently constrained. Incrementality is for humans without AI and without a 99% working version."

**Revised approach:**
- Migrate/transform existing code (not rebuild from scratch)
- Define constraints carefully, then execute boldly
- Single regression test as quality gate
- Let AI handle massive mechanical refactoring

**Created constraint questionnaire:** `00-constraint-questions.md` with 18 questions covering:
- Project structure and organization
- Build system migration approach
- Database and event log strategy
- Frontend technology and timing
- Testing scope and execution level
- Development workflow
- Migration strategy (preserve vs refactor)
- AI autonomy level

### Decision: Thorough Constraint Definition
User chose Option 2: Discuss constraints thoroughly, a few at a time, to understand implications before committing.

### Critical Insight: The Real Goal (Discussion on Maven vs Gradle)

**This is a testing strategy research project**, not just an app migration.

**The Vision:**
- Humans specify behavior (what system should do)
- AI implements everything (how system does it)
- Tests verify behavior only (no implementation coupling)
- Every logic path covered, fast as unit tests, no implementation knowledge

**Why Kotlin Multiplatform is mandatory:**
- Same language frontend/backend enables unified testing
- Can inject test doubles at ANY boundary (including UI)
- Tests written in one language, exercise full stack
- No HTTP mocking, no database stubbing - pure behavior verification

**The Architecture:**
- **Event log = behavior specification** (source of truth)
- **Dual projections prove correctness:**
  - MySQL: Reference implementation (straightforward relational)
  - DynamoDB: Optimized implementation (single-table, fast user operations)
  - Both must produce identical results from same events
- **Admin interface = test oracle:**
  - Can query event log directly
  - Can query both projections
  - Can compare results (debug mismatches)
  - No performance requirements (relational-style queries okay)
- **Access pattern separation:**
  - User operations: Optimized DynamoDB (<100ms)
  - Admin operations: Can scan, can be slow, relational queries

**Testing strategy:**
```kotlin
// Test specifies behavior as event
eventLog.append(UserRegistered(...))

// Both projections sync
mysqlProjection.sync()
dynamoProjection.sync()

// Test verifies equivalence (correctness proof!)
assertEquals(mysqlRepo.find(id), dynamoRepo.find(id))
```

**Why this matters:** The Condorcet app is 99% complete and proven. We're transforming it to explore this novel testing methodology. The app is the vehicle; the testing strategy is the research.

**Test Interaction Level - The Ambitious Goal:**
Tests interact at ViewModel/UI level, reading like human browser interactions:
```kotlin
app.user.inputLabeled("email").typeText("foo@bar.com")
app.user.pressButtonLabeled("Register")
app.user.navigateTo.createElectionPage()
```

**Not Selenium** - Tests drive ViewModels directly (no browser). Fast, no HTTP mocking, pure behavioral descriptions. The test framework provides human-readable DSL that manipulates Compose components/ViewModels underneath.

**This is possible because:**
- Same language (Kotlin) for frontend + backend + tests
- ViewModels are injectable (can use test integrations)
- Compose components testable without browser
- Tests read like BDD specs but execute at unit test speed

### Recording Approach
User wants to document the entire AI collaboration process for educational purposes. Created this development journal to:
- Chronicle decisions and rationale
- Show AI-human collaboration process
- Provide narrative that explains "how we got here"
- Supplement technical docs with story of development

---

## Constraint Decisions

### Batch 1: Project Foundation ✅

#### Q1: Project Structure
**Status:** ✅ Decided
**Options considered:**
- Flat modules (7 top-level: domain, contract, backend, frontend, deploy, local, integration)
- Hierarchical (shared/, server/, client/, infrastructure/, test/)
**Decision:** Flat modules (Option A)
**Rationale:** Simpler navigation, natural fit for Gradle multiplatform, clear single purpose per module

#### Q2: Package Naming
**Status:** ✅ Decided
**Options considered:**
- `com.seanshubin.vote.*` (new)
- `com.seanshubin.condorcet.*` (preserve)
**Decision:** `com.seanshubin.vote.*` (Option A)
**Rationale:** Clean break, shorter name, "vote" is the application, "condorcet" is the algorithm

#### Q3: Module Migration Strategy
**Status:** ✅ Decided
**Options considered:**
- Flatten 15+ Maven modules into packages
- Preserve as Gradle subprojects
- Hybrid approach
**Decision:** Flatten (Option A)
**Rationale:** Most are small utilities, multiplatform works better with fewer modules, domain organization preferred over technical layering per coding standards

**Backend package structure:**
```
backend/src/main/kotlin/com/seanshubin/vote/backend/
├── server/          # Jetty HTTP server
├── service/         # Business logic
├── database/        # SQL + DynamoDB repositories
├── auth/            # JWT + crypto
├── email/           # Email sending
├── http/            # HTTP utilities
├── json/            # JSON utilities
└── dependencies/    # Composition roots (Integrations/Bootstrap/Application)
```

### Batch 2: Build System & Data Strategy ✅

#### Q4: Build System Migration
**Status:** ✅ Decided
**Options considered:**
- AI converts all pom.xml → build.gradle.kts automatically
- Manual Gradle setup
**Decision:** AI automated conversion (Option A)
**Rationale:** Kotlin Multiplatform requires Gradle (mandatory for testing research). 15+ pom.xml files, conversion is mechanical. Gradle's flexibility needed for novel testing architecture. Will preserve dependency structure but translate to Gradle Kotlin DSL.

#### Q5: Database for Regression Test
**Status:** ✅ Decided
**Options considered:**
- H2 only (fast, simple)
- Testcontainers MySQL (prod match)
- DynamoDB Local + H2 (dual projections)
**Decision:** DynamoDB Local + H2 (Option C)
**Rationale:** Testing strategy requires both projections. H2 = reference implementation (MySQL), DynamoDB Local = optimized implementation. Tests verify both produce identical results - this is the correctness proof. Admin interface can query both to debug mismatches.

#### Q6: Event Log Implementation
**Status:** ✅ Decided (Revised from "defer")
**Options considered:**
- DynamoDB table with streams
- File-based append-only log
- MySQL event table (existing)
- Defer implementation
**Decision:** DynamoDB table from day 1
**Rationale:** Events ARE the behavior specification - cannot be deferred. Event log is source of truth. All mutations append events. Both projections (MySQL + DynamoDB) rebuild from events. Simple table: PK=eventId, SK=timestamp, payload=JSON.

#### Q7: DynamoDB Implementation Scope
**Status:** ✅ Decided (Revised from "defer")
**Options considered:**
- Full implementation day 1 alongside MySQL
- Stub/interface only
- MySQL only, add DynamoDB later
**Decision:** Full implementation of both projections from day 1 (Option A revised)
**Rationale:** Testing strategy requires comparing projections. MySQL = reference (relational model, straightforward queries). DynamoDB = optimized (single-table design, fast user operations). Tests verify: events → MySQL = events → DynamoDB. This proves DynamoDB implementation correctness.

#### Q13: Dependency Injection Pattern (pulled forward)
**Status:** ✅ Decided
**Options considered:**
- Preserve existing manual composition exactly
- Introduce Integrations/Bootstrap/Application stages
**Decision:** Integrations/Bootstrap/Application stages (Option B)
**Rationale:** Essential for testing research. Integrations layer allows swapping projections in tests. Can inject MySQL projection, DynamoDB projection, or BOTH to compare. Follows coding standards explicitly. Clean separation enables novel testing architecture.

**DI Structure:**
```kotlin
interface Integrations {
    val clock: Clock
    val eventLog: EventLog
    val projectionType: ProjectionType // MySQL, DynamoDB, or Both
}

// Tests can inject both projections and compare
class TestIntegrations(
    override val projectionType: ProjectionType = Both
) : Integrations {
    val mysqlProjection = InMemoryMySqlProjection()
    val dynamoProjection = InMemoryDynamoProjection()

    fun verifyEquivalence() {
        assertEquals(mysqlProjection.state, dynamoProjection.state)
    }
}
```

### Batch 3: Frontend & Testing Strategy ✅

#### Q8: Frontend Technology
**Status:** ✅ Decided
**Options considered:**
- Compose for Web (Kotlin UI)
- Keep React
- Kotlin/JS + React wrappers
**Decision:** Compose for Web confirmed
**Rationale:** Essential for testing research. Same language as backend enables ViewModel testing. Tests can inject dependencies into UI components. No HTTP/browser layer - tests drive ViewModels directly. Makes human-readable test DSL possible.

#### Q9: Frontend Migration Timeline
**Status:** ✅ Decided
**Options considered:**
- After backend test passes (sequential)
- In parallel with backend
- Backend only initially
**Decision:** After backend test passes (Option A)
**Rationale:** Prove testing methodology at backend level first (event log + dual projections). Backend has higher complexity. Once backend patterns work, apply same to frontend. Reduces debugging surface area.

**Phasing:**
- Milestone 1: Backend + events + dual projections, backend-only regression test
- Milestone 2: Add frontend, extend test to exercise ViewModels with human-readable DSL

#### Q10: Regression Test Scope
**Status:** ✅ Decided
**Test covers:** Complete user journey (happy path)
1. Register admin user
2. Register voter users
3. Admin creates election
4. Admin sets candidates
5. Admin sets eligible voters
6. Admin launches election
7. Voters cast ballots (ranked choice)
8. Admin finalizes election
9. View tally results
10. Admin deletes election
11. Admin deletes users
12. Verify system empty (both projections)

**Verification at each step:**
```kotlin
// After each action, verify projections match
integrations.verifyEquivalence()
```

**Rationale:** Tests full feature set, event sourcing, projection synchronization, complete cleanup capability.

#### Q11: Regression Test Execution Level (REVISED)
**Status:** ✅ Decided
**Options considered:**
- HTTP API level (realistic but slow)
- Service interface level (faster, behavioral boundary)
- ViewModel/UI level (human-readable, ambitious goal)
**Decision:** ViewModel/UI level (Option C - the ambitious goal)

**Intermediate step:** Start at Service level for backend-only milestone, then elevate to ViewModel level when frontend added.

**Target test style (Milestone 2 with frontend):**
```kotlin
@Test
fun completeElectionLifecycle() {
    val app = TestApplication(testIntegrations)

    // Register admin (reads like human interaction)
    app.user.navigateTo.registerPage()
    app.user.inputLabeled("username").typeText("admin")
    app.user.inputLabeled("email").typeText("admin@test.com")
    app.user.inputLabeled("password").typeText("password")
    app.user.pressButtonLabeled("Register")

    // Behavioral assertion
    assertTrue(app.currentPage is DashboardPage)
    app.integrations.verifyEquivalence()

    // Create election (reads like clicking through UI)
    app.user.pressButtonLabeled("Create Election")
    app.user.inputLabeled("election name").typeText("Best Language")
    app.user.inputLabeled("candidate").typeText("Kotlin")
    app.user.pressButtonLabeled("Add Candidate")
    // ... etc
}
```

**Rationale:** Tests read like BDD specifications. No implementation coupling. Fast (no browser). Drives ViewModels/Compose components directly. Human-readable behavioral descriptions. This is the research goal - prove this testing methodology works.

---

### Batch 4: Migration Strategy & Execution

#### Q12: Local Development Execution
**Status:** ✅ Decided
**Decision:** Single Gradle task `./gradlew run` (Option A)
**Rationale:** One command starts DynamoDB Local, H2, backend (:8080), frontend dev server (:3000). Matches testing strategy (both databases). Fast feedback loop.

#### Q14: Code Migration - Preserve or Refactor
**Status:** ✅ Decided
**Decision:** Refactor during migration (Option B)
**Rationale:** Already transforming architecture (event sourcing, dual projections). Coding standards enable testing strategy (DI essential). AI handles mechanical refactoring. Focus: Integrations/Bootstrap/Application pattern, event definitions, interface-based repositories.

#### Q15: AWS Deployment Initial Target
**Status:** ✅ Decided
**Decision:** Defer entirely (Option C)
**Rationale:** Prove testing methodology locally first. AWS adds complexity. Phasing: Milestone 1 (backend local), Milestone 2 (frontend + ViewModel tests local), Milestone 3 (AWS with real DynamoDB).

#### Q16: External Dependencies - Preserve or Upgrade
**Status:** ✅ Decided
**Decision:** Upgrade to latest STABLE versions
**Rationale:** After analyzing consequences, user decided to be aggressive with stable versions. Core Kotlin ecosystem (Kotlin 2.0.21, Compose 1.7.1, kotlinx.serialization 1.7.3, kotlinx-coroutines 1.9.0) upgraded to latest stable - critical for multiplatform research. Infrastructure (Jetty 11.x latest, MySQL connector, AWS SDK) uses proven stable versions. Java 21 LTS. Avoids bleeding edge (dev/RC/alpha versions). Provides modern features with stable foundation.

**Key change:** Switch from Jackson to kotlinx.serialization (multiplatform native, enables shared event definitions across frontend/backend/tests).

**Version targets:**
```kotlin
kotlin = "2.0.21"
compose-multiplatform = "1.7.1"
kotlinx-serialization = "1.7.3"
kotlinx-coroutines = "1.9.0"
gradle = "8.10"
java = "21"
jetty = "11.0.24" // Latest 11.x
mysql-connector = "8.0.33"
aws-sdk-kotlin = "1.3.x"
testcontainers = "1.20.4"
junit = "5.11.3"
```

#### Q17: Existing Tests
**Status:** ✅ Decided
**Decision:** Discard completely
**Rationale:** Use current regression test as inspiration for new test, but don't be constrained. Testing methodology is fundamentally different. Old tests verify implementation details; new test verifies behavior through ViewModel DSL.

#### Q18: AI Autonomy Level
**Status:** ✅ Decided
**Decision:** Batches of related changes (Option C)
**Examples:** "Migrate domain module" (10-15 files, show summary), "Set up event log + projections" (architectural, show structure). Balance: not too granular (slow), not too coarse (lose visibility).

#### Q-Extra-1: DynamoDB Access Patterns
**Status:** ✅ Decided
**Decision:** Create AFTER MySQL works (Option B)
**Rationale:** MySQL is straightforward reference implementation. Prove dual-projection concept with simpler relational model first. Once MySQL works and actual query patterns observed, optimize DynamoDB single-table schema based on real usage. Less risk - validate architecture before optimizing.

#### Q-Extra-2: Test DSL Design
**Status:** ✅ Decided
**Decision:** Design upfront (Option A)
**Rationale:** The DSL IS the behavioral specification interface. Designing it first ensures frontend built to be testable from start. Will create Test DSL specification document before frontend implementation begins. Prevents "frontend not testable" problem.

**Test DSL API (to be fully specified):**
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
```

---

## All Constraints Finalized ✅

All 20 constraint questions answered. See `00-constraint-summary.md` for complete execution blueprint.

---

## Implementation Log

_This section will track major implementation milestones..._

---

## Lessons Learned

_This section will capture insights as we go..._

---

## Next Session Preview

_What we plan to tackle next..._
