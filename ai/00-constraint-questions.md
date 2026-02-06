# Constraint Questions: Define the Approach

## Philosophy

We have:
- ✅ 99% feature-complete working application
- ✅ AI capable of massive mechanical refactoring
- ✅ Single regression test as quality gate

We will:
- Migrate/transform existing code (not rebuild from scratch)
- Apply constraints, then execute boldly
- Let the single happy path test tell us if we succeeded

## Questions to Constrain the Approach

### 1. Project Structure

**Q: What should the final directory structure look like?**

Option A - Flat modules:
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

Option B - Hierarchical:
```
vote/
├── shared/
│   ├── domain/      # commonMain
│   └── contract/    # commonMain
├── server/
│   ├── backend/     # jvmMain
│   └── local/       # jvmMain
├── client/
│   └── frontend/    # jsMain
├── infrastructure/
│   └── deploy/      # jvmMain
└── test/
    └── integration/ # jvmTest
```

Option C - Your preference: ___________

**Your decision:**

---

### 2. Package Naming Convention

**Q: What package naming convention?**

Current backend uses: `com.seanshubin.condorcet.backend.*`

Options:
- A) `com.seanshubin.vote.*` (new name, clean break)
- B) `com.seanshubin.condorcet.*` (preserve existing)
- C) Something else: ___________

**Your decision:**

---

### 3. Migration Strategy: Transform or Preserve Structure?

**Q: Should we preserve the existing multi-module Maven structure in Gradle, or flatten/reorganize?**

Current backend has 15+ Maven modules (console, dependencies, server, service-http, jwt, http, service, logger, contract, database, domain, genericdb, io, crypto, json, string-util, mail, configuration-util, global-constants, prototype).

Options:
- A) **Flatten**: Merge most into `backend/` as packages (backend/jwt, backend/crypto, backend/database, etc.)
- B) **Preserve**: Keep as Gradle subprojects (:backend:jwt, :backend:crypto, etc.)
- C) **Hybrid**: Core modules as subprojects (domain, database, service), utils flattened into packages

**Your decision:**

---

### 4. Build System Migration

**Q: Migrate Maven POMs to Gradle programmatically or manually?**

Options:
- A) Let AI convert all pom.xml files to build.gradle.kts in one shot
- B) Manual setup of build files, copy code afterward

**Your decision:**

---

### 5. Database Strategy for Regression Test

**Q: What database should the single regression test use?**

The test needs to run the actual application. Options:
- A) **In-memory H2** - Fast, no Docker, SQL dialect differences acceptable
- B) **Testcontainers MySQL** - Exact prod match, requires Docker
- C) **DynamoDB Local + H2** - Closer to AWS prod, requires Docker for Dynamo
- D) **DynamoDB Local only** - AWS-native, requires Docker, no relational DB

**Your decision:**

---

### 6. Event Log Implementation

**Q: Which event log technology for initial implementation?**

(We can swap later, but need to pick one to start)

Options:
- A) **DynamoDB table** (append-only writes, streams for projection)
- B) **File-based** (append-only log files, simple for local)
- C) **Table in MySQL/H2** (simplest migration from current event table)
- D) **Defer** (use existing event table approach initially, refactor later)

**Your decision:**

---

### 7. DynamoDB Implementation Scope

**Q: Should DynamoDB be fully implemented initially, or stubbed?**

Options:
- A) **Full implementation** - All repositories have both MySQL and DynamoDB implementations from day 1
- B) **Stub/Interface only** - Define interfaces, implement MySQL fully, stub DynamoDB (returns empty/errors)
- C) **MySQL only** - Get everything working with MySQL, add DynamoDB after test passes
- D) **DynamoDB only** - Skip MySQL, go straight to DynamoDB (risky but simple)

**Your decision:**

---

### 8. Frontend Technology Verification

**Q: Confirm Compose for Web, or consider alternatives?**

Compose for Web is still maturing. Options:
- A) **Compose for Web** - Kotlin multiplatform UI, fresh design (as proposed)
- B) **Compose for Web + fallback** - Try Compose, have React as backup plan if issues
- C) **Keep React** - Migrate to TypeScript, share contracts via OpenAPI/generated types
- D) **Kotlin/JS + React** - Use Kotlin/JS with React wrappers (kotlin-react)

**Your decision:**

---

### 9. Frontend Migration Timeline

**Q: When should we tackle the frontend?**

Options:
- A) **After backend test passes** - Backend first, then frontend
- B) **In parallel** - Backend and frontend simultaneously (AI can handle both)
- C) **Backend only initially** - Get backend deployed to AWS, defer frontend (use existing React temporarily)

**Your decision:**

---

### 10. Regression Test Scope

**Q: What exactly should the single regression test cover?**

Options:
- A) **Happy path only** - Register users, create election, vote, tally, delete everything (no error cases)
- B) **Happy path + critical errors** - Happy path + test auth failures, permission errors (5-10 negative tests)
- C) **Your specification** - Tell me exactly what the test should do

**Your decision:**

---

### 11. Regression Test Execution Level

**Q: Where does the test interact with the system?**

Options:
- A) **HTTP API** - Test makes real HTTP requests to running server (most realistic)
- B) **Service interface** - Test calls Service methods directly (faster, no HTTP overhead)
- C) **Repository layer** - Test calls repositories directly (fastest, least realistic)

**Your decision:**

---

### 12. Local Development Execution

**Q: How should local development work?**

Options:
- A) **Single Gradle task** - `./gradlew run` starts everything (backend + frontend dev server)
- B) **Separate tasks** - `./gradlew :backend:run` and `./gradlew :frontend:browserDevelopmentRun` in separate terminals
- C) **Docker Compose** - `docker-compose up` starts everything (backend, frontend, databases)
- D) **Your preference**: ___________

**Your decision:**

---

### 13. Dependency Injection: Preserve Existing Pattern?

**Q: The backend uses manual composition (dependencies module). Preserve exactly, or adapt?**

Current pattern:
- Manual composition roots
- No DI framework
- Constructor injection

Options:
- A) **Preserve exactly** - Keep the same pattern, adapt for multiplatform
- B) **Introduce Integrations/Bootstrap/Application stages** (per your coding standards)
- C) **Something else**: ___________

**Your decision:**

---

### 14. Code Migration: Preserve or Refactor?

**Q: When migrating backend code, should we refactor to match coding standards, or preserve as-is?**

Your coding standards are strict. The existing code may have violations. Options:
- A) **Preserve as-is** - Get it working first, refactor later
- B) **Refactor during migration** - Apply all coding standards as we move code
- C) **Hybrid** - Fix obvious violations (missing DI, anonymous code), defer others (package structure)

**Your decision:**

---

### 15. AWS Deployment: Initial Target

**Q: What should the first AWS deployment look like?**

Options:
- A) **Full architecture** - ECS Fargate + ALB + DynamoDB + RDS (everything from day 1)
- B) **Simplified** - ECS Fargate + RDS only (defer DynamoDB until it's implemented)
- C) **Defer entirely** - Get local working first, deploy later
- D) **Containerized from start** - Even local dev uses Docker (matches prod exactly)

**Your decision:**

---

### 16. External Dependencies: Preserve or Upgrade?

**Q: Should we preserve exact library versions, or upgrade?**

Current backend uses:
- Kotlin 1.7.20 (latest is 2.1.0+)
- Jetty 11.0.12 (latest is 12.x)
- Jackson 2.13.4 (latest is 2.18.x)
- etc.

Options:
- A) **Preserve** - Use exact versions from current backend (minimize risk)
- B) **Upgrade** - Use latest stable versions (fresh start)
- C) **Hybrid** - Upgrade Kotlin and build tools, preserve runtime dependencies

**Your decision:**

---

### 17. Existing Tests: Preserve or Discard?

**Q: The backend has existing unit tests. What should we do with them?**

Options:
- A) **Discard** - Single regression test is the only test (radical but consistent with your approach)
- B) **Preserve** - Migrate existing tests, keep as additional safety net
- C) **Selective** - Keep domain logic tests (Condorcet algorithm), discard infrastructure tests

**Your decision:**

---

### 18. Execution Constraint: How Much Should AI Do Autonomously?

**Q: What's the largest unit of work you want me to execute without checking in?**

Options:
- A) **Complete phases** - "Do Phase 1" and I do entire phase, report back when done
- B) **Individual files** - Show you each file before writing
- C) **Batches of related changes** - "Migrate domain module" and I do ~10 files, show summary
- D) **Your preference**: ___________

**Your decision:**

---

## Summary Template

Once you've answered these, I'll have a clear execution framework:

```
Structure: [flat/hierarchical/custom]
Package: com.seanshubin.[vote/condorcet].*
Migration: [flatten/preserve/hybrid]
Build migration: [automated/manual]
Test DB: [H2/MySQL/DynamoDB Local/mix]
Event log: [DynamoDB/file/MySQL table/defer]
DynamoDB scope: [full/stub/defer]
Frontend tech: [Compose for Web/other]
Frontend timing: [after backend/parallel/defer]
Test scope: [happy path only/+ errors/custom]
Test level: [HTTP/Service/Repository]
Local dev: [single command/separate/docker]
DI pattern: [preserve/adapt with stages/other]
Refactoring: [preserve/refactor during/hybrid]
AWS deployment: [full/simplified/defer]
Dependencies: [preserve/upgrade/hybrid]
Existing tests: [discard/preserve/selective]
Autonomy: [complete phases/individual files/batches]
```

**I will not start until you've decided these constraints.**

What's the best way to work through these? One at a time, or do you want to just tell me your preferences in bulk?
