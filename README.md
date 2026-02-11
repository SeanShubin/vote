# Vote - Condorcet Voting System

A ranked-choice voting system demonstrating clean architecture with multiple database backends unified behind a relational projection layer.

## Overview

This project implements a Condorcet voting system (ranked-choice with pairwise comparisons) with three different database backends that share the same API and business logic:

1. **InMemory** - Map-based storage for fast testing
2. **MySQL** - Traditional relational database with foreign keys
3. **DynamoDB** - Production-ready single-table NoSQL design

**Key architectural insight**: All three backends expose the same `QueryModel` interface using natural keys (election names, voter names), hiding implementation details (SQL joins, DynamoDB composite keys, etc.). This lets developers write tests and admin tools against a simple relational model while production uses optimized storage patterns.

## For New Engineers

### Entry Points

The application has two main entry points:

- **Backend**: `backend/src/main/kotlin/com/seanshubin/vote/backend/Main.kt`
  - Implements staged dependency injection pattern
  - Creates `ProductionIntegrations` (I/O boundaries)
  - Wires `ApplicationDependencies` (services, repositories)
  - Starts Jetty HTTP server

- **Frontend**: `frontend/src/jsMain/kotlin/com/seanshubin/vote/frontend/Main.kt`
  - Creates `ProductionFrontendIntegrations` (ApiClient)
  - Renders Compose for Web UI
  - Connects to backend HTTP API

Both entry points follow the same pattern: create integrations → wire dependencies → run.

### Test Suites

Tests are organized by concern and can be run independently:

```bash
# Backend unit tests (71 tests) - InMemory backend, fast
./gradlew :backend:test

# Frontend tests (32 tests) - FakeApiClient, no browser needed
./gradlew :frontend:jsTest

# Integration tests (48 tests) - HTTP API boundary tests
./gradlew :integration:test

# Run all tests
./gradlew test
```

**Test organization**:
- `backend/src/test/kotlin/` - Business logic tests using InMemory backend
- `frontend/src/jsTest/kotlin/` - Frontend behavior tests with FakeApiClient
- `integration/src/test/kotlin/` - HTTP API tests with real backend
- All tests are self-documenting with descriptive names

**Test philosophy**: Tests use injected interfaces (fake I/O boundaries) rather than mocking internal collaborators. This enables fast, reliable, complete test coverage without heavyweight dependencies.

### Static Analysis Documentation

Generate and view static analysis reports:

```bash
# Generate all static analysis reports
./scripts/generate-docs.sh

# View the report index
open schema-diagram/index.html
```

The reports include:
- Package dependencies (cycles, vertical dependencies)
- Module structure
- Code organization metrics
- Schema diagrams
- Architectural compliance

Configuration is in `code-structure-config.json`.

### Running the Application Locally

**Backend** (choose one database):

```bash
# Option 1: DynamoDB Local (recommended)
./scripts/db-setup-dynamodb
./scripts/run-backend-dynamodb

# Option 2: MySQL
./scripts/db-setup-mysql
./scripts/run-backend-mysql

# Option 3: InMemory (testing only)
./gradlew :backend:run
```

Backend starts on `http://localhost:8080`

**Frontend**:

```bash
# Development build with webpack dev server
./gradlew :frontend:jsBrowserDevelopmentRun

# Production build
./gradlew :frontend:jsBrowserProductionWebpack
```

Frontend serves on `http://localhost:8088` (development) and connects to backend at `http://localhost:8080`.

**Create test data**:

```bash
# After starting backend, create test election and ballots
./scripts/setup-test-ballot dynamodb  # or mysql
```

**Verify data**:

```bash
# View relational projection (admin view)
./scripts/inspect-dynamodb-all

# View raw storage (debug view)
./scripts/inspect-dynamodb-raw-all
```

## Quick Start

### Prerequisites

- Java 17 or later
- Docker (for MySQL and DynamoDB Local)
- Gradle (wrapper included)

### Run with DynamoDB (Single-Table NoSQL)

```bash
# Setup DynamoDB Local
./scripts/db-setup-dynamodb

# Run backend
./scripts/run-backend-dynamodb &

# Create test data
./scripts/setup-test-ballot dynamodb

# Inspect data (relational projection)
./scripts/inspect-dynamodb-all

# Inspect data (raw storage)
./scripts/inspect-dynamodb-raw-all
```

### Run with MySQL (Relational)

```bash
# Setup MySQL
./scripts/db-setup-mysql

# Run backend
./scripts/run-backend-mysql &

# Create test data
./scripts/setup-test-ballot mysql

# Inspect data
./scripts/inspect-mysql-all

# Or connect directly
mysql -h localhost -u vote -p vote  # password: vote
```

### Run Tests (InMemory)

```bash
./gradlew :backend:test
```

## Architecture

### Three-Layer Design

```
┌─────────────────────────────────────────┐
│  HTTP API (SimpleHttpHandler)          │  ← Jetty handles HTTP
└─────────────────────────────────────────┘
                  ↓
┌─────────────────────────────────────────┐
│  Service Layer (Business Logic)         │  ← Validates rules, coordinates
└─────────────────────────────────────────┘
                  ↓
┌──────────────┬──────────────┬──────────────┐
│  EventLog    │ CommandModel │  QueryModel  │  ← Repository abstractions
└──────────────┴──────────────┴──────────────┘
                  ↓
┌──────────────┬──────────────┬──────────────┐
│   InMemory   │    MySQL     │   DynamoDB   │  ← Concrete implementations
└──────────────┴──────────────┴──────────────┘
```

### The Relational Projection Layer

The `QueryModel` interface defines operations using **natural keys**:

```kotlin
interface QueryModel {
    fun findUserByName(name: String): User?
    fun searchBallot(voterName: String, electionName: String): BallotSummary?
    fun listCandidates(electionName: String): List<String>
    fun listBallots(electionName: String): List<RevealedBallot>
}
```

No matter which database backend is used, methods accept human-readable names and return domain objects with natural keys. Implementation details are completely hidden:

- **MySQL**: Uses `SELECT * FROM ballot WHERE election_name = ? AND voter_name = ?`
- **DynamoDB**: Uses `GetItem(PK=ELECTION#name, SK=BALLOT#voter)` then transforms to natural keys

Tests, admin tools, and debugging all work with this relational projection, making the system easy to understand and verify.

### Database Backend Comparison

| Aspect | InMemory | MySQL | DynamoDB Single-Table |
|--------|----------|-------|----------------------|
| **Storage** | `Map<String, Entity>` in memory | Relational tables with FKs | 2 tables: `vote_data` + `vote_event_log` |
| **Keys** | Natural keys (names) | Natural key PKs: `user(name)`, `election(name)` | Composite: `PK=ENTITY#id`, `SK=SUBENTITY#id` |
| **Relationships** | In-memory references | Foreign keys | Partition key grouping |
| **Queries** | HashMap lookups | SQL SELECT with JOINs | Query with `begins_with` on SK |
| **Use Case** | Testing, no dependencies | Educational, relational comparison | Production, idiomatic NoSQL |

## Data Flow: API Call → Database

Complete end-to-end trace showing what happens when a user casts a ballot:

1. **HTTP Request**: `POST /election/Favorite%20Language/ballot`
2. **Handler**: Extracts election name, deserializes JSON
3. **Service**: Validates business rules (eligible voter, voting allowed)
4. **EventLog**: Records `VoteCast` event for audit trail
5. **CommandModel**: Writes ballot to database
   - MySQL: `INSERT INTO ballot (election_name, voter_name, rankings, ...)`
   - DynamoDB: `PutItem` with `PK=ELECTION#name, SK=BALLOT#voter`
6. **Response**: Returns confirmation code

See **[docs/api-to-database-flow.md](docs/api-to-database-flow.md)** for complete code-level trace with actual implementations.

## Key Features

### Event Sourcing

Every command (register user, cast ballot, create election) is recorded as an event:

```kotlin
sealed class DomainEvent {
    data class UserRegistered(val userName: String, val email: String, ...) : DomainEvent()
    data class VoteCast(val voterName: String, electionName: String, rankings: List<Ranking>) : DomainEvent()
    // ...
}
```

Events are:
- **Append-only**: Never modified or deleted
- **Authoritative**: Events are the source of truth
- **Replayable**: Can rebuild state from events

### Natural Keys vs Surrogate Keys

This project uses **natural keys** throughout:
- Users identified by `userName` (not auto-increment user_id)
- Elections identified by `electionName` (not election_id)
- Ballots identified by `(electionName, voterName)` composite

**Why?** Natural keys make debugging and testing obvious. When you see `"alice voted in Favorite Language"`, you immediately understand what happened. No need to look up IDs in other tables.

### Dependency Injection

All I/O boundaries are injected through the `Integrations` interface:

```kotlin
interface Integrations {
    val clock: Clock           // Time (mockable for tests)
    val files: FilesContract   // File system
    val emit: (String) -> Unit // Console output
}
```

This makes the entire application testable without real infrastructure.

## Project Structure

```
vote/
├── backend/                         # Jetty server, repository implementations
│   └── src/main/kotlin/
│       ├── dependencies/            # ApplicationDependencies (wiring)
│       ├── http/                    # SimpleHttpHandler (HTTP routing)
│       ├── integration/             # ProductionIntegrations (I/O boundaries)
│       ├── repository/              # InMemory, MySQL, DynamoDB implementations
│       └── service/                 # ServiceImpl (business logic)
├── contract/                        # Interfaces (QueryModel, CommandModel, Service)
├── domain/                          # Domain models (User, Election, Ballot, Events)
├── scripts/                         # Database setup and inspection scripts
└── docs/
    ├── api-to-database-flow.md      # Complete data flow trace
    ├── architectural-insight.md     # Design philosophy
    ├── debugging-workflow.md        # Two-layer debugging approach
    ├── dynamodb-single-table-design.md  # DynamoDB schema details
    └── dynamodb-access-patterns.md  # Query patterns and optimizations
```

## Documentation

### For Developers

- **[docs/api-to-database-flow.md](docs/api-to-database-flow.md)** - Start here! Traces complete path from HTTP request to raw database storage
- **[docs/architectural-insight.md](docs/architectural-insight.md)** - Why relational projection layer + optimized storage
- **[docs/debugging-workflow.md](docs/debugging-workflow.md)** - How to debug using relational projection vs raw storage
- **[schema-diagram/README.md](schema-diagram/README.md)** - Auto-generated schema diagrams (GraphViz, Mermaid, HTML)

### For Operators

- **Database Setup**: `./scripts/db-setup-{dynamodb|mysql}`
- **Inspection** (Relational): `./scripts/inspect-{dynamodb|mysql}-all`
- **Inspection** (Raw): `./scripts/inspect-{dynamodb|mysql}-raw-*`
- **Reset Database**: `./scripts/db-reset-{dynamodb|mysql}`

### Design Documentation

- **[docs/dynamodb-single-table-design.md](docs/dynamodb-single-table-design.md)** - Single-table schema, entity patterns, access patterns
- **[docs/dynamodb-access-patterns.md](docs/dynamodb-access-patterns.md)** - Query frequency analysis, optimization priorities
- **[docs/dynamodb-design-summary.md](docs/dynamodb-design-summary.md)** - Executive summary of design decisions

## Debugging Workflow

The system provides **two views** of data:

### 1. Relational Projection (Admin View)

Uses QueryModel interface, shows natural keys:

```bash
./scripts/inspect-dynamodb-all
```

Output:
```
Users:          2 records
Elections:      1 record
Ballots:        2 records

Election: Favorite Language
Owner:    alice
Candidates: Kotlin, Python, Java, Rust

Voter: alice
Rankings: [Kotlin > Python > Rust > Java]
```

**What you see**: Conceptual model with human-readable names.

### 2. Raw Storage (Debug View)

Bypasses QueryModel, shows actual database structure:

```bash
./scripts/inspect-dynamodb-raw-keys
```

Output:
```
PK                            SK                  Entity Type
ELECTION#Favorite Language    BALLOT#alice        BALLOT
ELECTION#Favorite Language    CANDIDATE#Kotlin    CANDIDATE
USER#alice                    METADATA            USER
```

**What you see**: Mechanical storage with composite keys (DynamoDB) or table schemas (MySQL).

**Debugging strategy**:
1. Check relational projection first (understand WHAT is wrong)
2. Check raw storage (understand HOW it's stored)
3. Compare the two (find WHERE the bug is - projection vs storage)

See **[docs/debugging-workflow.md](docs/debugging-workflow.md)** for complete guide.

## Testing

### Unit Tests (InMemory Backend)

```bash
./gradlew :backend:test
```

Tests run against InMemory backend - fast, no dependencies.

### Integration Tests (MySQL/DynamoDB)

```bash
# Setup database
./scripts/db-setup-dynamodb

# Run backend
./scripts/run-backend-dynamodb &

# Run test script
./scripts/setup-test-ballot dynamodb

# Verify
./scripts/inspect-dynamodb-all
```

### Test Philosophy

Tests are written against the `QueryModel` interface using natural keys. This means:
- ✅ Same tests work for all three backends (InMemory, MySQL, DynamoDB)
- ✅ Tests don't know about implementation details (PK/SK, SQL, etc.)
- ✅ Swapping backends requires zero test changes

Example test:
```kotlin
@Test
fun `user can cast ballot in election`() {
    // Uses natural keys - works with any backend
    service.createElection(owner, CreateElectionRequest("Favorite Language"))
    service.setCandidates(owner, "Favorite Language", listOf("Kotlin", "Python"))
    service.castBallot(voter, CastBallotRequest("Favorite Language", rankings))

    val ballot = queryModel.searchBallot("alice", "Favorite Language")
    assertNotNull(ballot)
    assertEquals("Kotlin", ballot.rankings[0].candidateName)
}
```

## API Endpoints

### User Management

- `POST /register` - Register new user
- `POST /login` - Authenticate user

### Election Management

- `POST /election` - Create election
- `PUT /election/{name}/candidates` - Set candidates
- `PUT /election/{name}/eligibility` - Set eligible voters
- `POST /election/{name}/launch` - Open voting
- `GET /election/{name}/tally` - Get results

### Voting

- `POST /election/{name}/ballot` - Cast or update ballot
- `GET /election/{name}/ballot` - View your ballot

See `SimpleHttpHandler.kt` for complete API specification.

## Design Principles

1. **Natural Keys Everywhere** - Users, elections, ballots identified by human-readable names
2. **Relational Projection** - QueryModel hides implementation details, exposes relational view
3. **Event Sourcing** - Every command produces an append-only event
4. **Dependency Injection** - All I/O boundaries injected through Integrations interface
5. **Three Backends, One Interface** - Proves abstraction works across storage paradigms
6. **Admin Tools Use Same Interface** - Debugging tools use QueryModel, not raw database access

## Contributing

### Adding a New Backend

To add a fourth backend (e.g., PostgreSQL, MongoDB):

1. Implement `QueryModel` interface in `backend/src/main/kotlin/repository/`
2. Implement `CommandModel` interface
3. Implement `EventLog` interface
4. Add configuration to `ApplicationDependencies.kt`
5. Run existing tests - they should pass unchanged!

The relational projection layer (QueryModel) defines the contract. Implementation details are your choice.

### Code Quality Standards

See `@rules/shared-standards/README.md` for architectural guidelines:
- Coupling and Cohesion
- Dependency Injection
- Event Systems
- Abstraction Levels
- Package Hierarchy

## License

MIT License - See LICENSE file for details.

## Contact

[Your contact information or repository link]
