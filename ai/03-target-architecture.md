# Target Architecture

## Single Kotlin Multiplatform Project

### Project Structure
```
vote/
├── shared/
│   ├── domain/               # Domain models, business logic
│   │   └── commonMain/       # Shared across JVM, JS, Native
│   │       ├── api/          # Domain interfaces
│   │       └── logic/        # Domain algorithms (Condorcet tally, etc.)
│   └── contract/             # Shared DTOs, API contracts
│       └── commonMain/
│           ├── request/      # API request models
│           ├── response/     # API response models
│           └── events/       # Event definitions
│
├── backend/
│   └── jvmMain/
│       ├── server/           # Jetty HTTP server
│       ├── service/          # Business logic implementation
│       ├── database/         # Database access
│       │   ├── relational/   # MySQL implementation
│       │   ├── dynamo/       # DynamoDB implementation
│       │   └── eventlog/     # Event sourcing implementation
│       ├── auth/             # JWT, crypto
│       ├── email/            # Email sending
│       ├── http/             # HTTP utilities
│       ├── json/             # JSON serialization
│       ├── config/           # Configuration management
│       └── dependencies/     # Composition roots
│
├── frontend/
│   └── jsMain/
│       ├── components/       # Compose for Web UI components
│       ├── pages/            # Top-level page components
│       ├── state/            # Client-side state management
│       ├── api/              # Backend API client
│       ├── theme/            # UI theming
│       └── App.kt            # Application entry point
│
├── deploy/
│   └── jvmMain/
│       ├── cdk/              # AWS CDK constructs
│       ├── docker/           # Container definitions
│       ├── config/           # Environment configs
│       └── console/          # Deployment CLI
│
├── local/
│   └── jvmMain/
│       ├── runner/           # Local execution orchestration
│       ├── embedded/         # Embedded database (H2 or testcontainers MySQL)
│       └── dev/              # Development utilities
│
└── test/
    └── commonTest/
        ├── integration/      # End-to-end tests
        └── regression/       # Single comprehensive regression test
```

### Build System
- **Gradle Kotlin DSL** (migration from Maven)
- **Kotlin Multiplatform Gradle Plugin**
- **Compose Multiplatform Plugin** (for Compose for Web)
- **AWS CDK with Gradle** (or keep Maven for CDK, integrate with Gradle build)

### Module Dependencies
```
shared (commonMain)
  ↑
  ├─ backend (jvmMain)
  ├─ frontend (jsMain)
  ├─ deploy (jvmMain)
  └─ local (jvmMain)
```

## Backend Architecture

### Service Layer (Preserves Existing API)
Interface `Service` remains unchanged - all existing endpoints preserved:
- Authentication: register, authenticate, refresh, changePassword
- User management: listUsers, getUser, updateUser, removeUser, setRole
- Election management: addElection, listElections, getElection, updateElection, deleteElection, launch, finalize
- Voting: castBallot, listRankings, tally, setEligibleVoters, etc.

### Data Layer - Multi-Database Abstraction

#### Event Log (Source of Truth)
**Technology Options (Decision Required):**
1. **File-based Append-Only Log** (simplest for local, challenges for AWS)
2. **DynamoDB Streams** (AWS-native, durable, scalable)
3. **Amazon Kinesis Data Streams** (high throughput, retention policies)
4. **S3 Event Storage** (durable, cost-effective, query via Athena)

**Requirements:**
- Append-only writes (immutable events)
- Structured event data (JSON or Avro)
- Extremely durable (no data loss)
- Fast writes (< 100ms)
- Replayable (rebuild read models)

**Event Schema:**
```kotlin
sealed class DomainEvent {
    abstract val eventId: UUID
    abstract val timestamp: Instant
    abstract val userId: String?

    data class UserRegistered(...)
    data class ElectionCreated(...)
    data class BallotCast(...)
    // ... all domain events
}
```

#### Relational Database (Read Model)
**Local**: H2 embedded or testcontainers MySQL
**AWS**: RDS MySQL (preserve current schema)

**Tables** (same as current):
- user, election, candidate, ballot, voter_eligibility, variable
- Materialized from event log via synchronization process

#### DynamoDB (Read Model for AWS Deployment)
**Access Patterns** (Prioritized):
1. **User Lookup by Email** (login) - GSI on email
2. **User Lookup by userName** (auth) - PK = userName
3. **List Elections for User** (homepage) - GSI on ownerName
4. **Get Election Detail** (election page) - PK = electionName
5. **List Candidates for Election** - Query by electionName
6. **Check Voter Eligibility** - Query by electionName + userName
7. **Get Ballot for Voter + Election** - Query by voterName + electionName
8. **List All Ballots for Election** (tally) - Query by electionName
9. **List Users** (admin) - Scan (infrequent, admin only)
10. **Count Users/Elections/Ballots** (dashboard) - Aggregation queries

**Table Design** (Single-Table or Multi-Table TBD):
```
Option A: Single Table Design
PK                     SK                      Attributes
USER#<userName>        PROFILE                 email, role, hash, salt
USER#<userName>        ELECTION#<electionName> ownerFlag
ELECTION#<name>        METADATA                status, allowEdit, ownerName
ELECTION#<name>        CANDIDATE#<name>        (candidate data)
ELECTION#<name>        VOTER#<userName>        eligibility
BALLOT#<voterName>     ELECTION#<electionName> rankings, confirmation
GSI1: email -> USER#<userName>

Option B: Multi-Table Design (simpler queries, higher cost)
- UserTable (PK: userName, GSI: email)
- ElectionTable (PK: electionName, GSI: ownerName)
- CandidateTable (PK: electionName#candidateName)
- BallotTable (PK: voterName#electionName, GSI: electionName)
- EligibilityTable (PK: electionName#voterName, GSI: electionName)
```

**Synchronization Strategy:**
- Event log publishes to both relational and DynamoDB projections
- Synchronization process reads events, updates both stores
- Eventual consistency acceptable (voting app, not financial)

### Database Abstraction Interfaces
```kotlin
// shared/domain/commonMain
interface UserRepository {
    suspend fun findByUserName(userName: String): User?
    suspend fun findByEmail(email: String): User?
    suspend fun save(user: User)
    suspend fun delete(userName: String)
    suspend fun listAll(): List<UserNameRole>
}

interface ElectionRepository {
    suspend fun findByName(name: String): Election?
    suspend fun save(election: Election)
    suspend fun delete(name: String)
    suspend fun listAll(): List<ElectionSummary>
    suspend fun listByOwner(ownerName: String): List<ElectionSummary>
}

// Similar interfaces for Candidate, Ballot, Eligibility repositories

// backend/jvmMain/database
class MySqlUserRepository : UserRepository { ... }
class DynamoDbUserRepository : UserRepository { ... }
```

### Dependency Injection (Per Coding Standards)

#### Integrations Stage (I/O Boundaries)
```kotlin
interface Integrations {
    val clock: Clock
    val files: FilesContract
    val database: DatabaseConnection  // or relational + dynamo separately
    val email: EmailSender
    val random: RandomSource
}

object ProductionIntegrations : Integrations { ... }
object LocalIntegrations : Integrations { ... }
object TestIntegrations : Integrations { ... }
```

#### Bootstrap Stage (Configuration Loading)
```kotlin
data class Configuration(
    val databaseUrl: String,
    val jwtSecret: String,
    val emailConfig: EmailConfig,
    val awsConfig: AwsConfig?,
    val serverPort: Int,
    val enableDynamoDb: Boolean,
    val enableRelationalDb: Boolean,
    val eventLogPath: String
)

class BootstrapDependencies(
    args: Array<String>,
    integrations: Integrations
) {
    private val configLoader = ConfigLoader(integrations.files)
    val configuration: Configuration = configLoader.load(args)
    val runner: Runnable = ApplicationDependencies.fromConfiguration(integrations, configuration).runner
}
```

#### Application Stage (Business Logic Wiring)
```kotlin
class ApplicationDependencies(
    integrations: Integrations,
    configuration: Configuration
) {
    // Repositories (choose based on config)
    private val userRepository: UserRepository = when {
        configuration.enableDynamoDb -> DynamoDbUserRepository(...)
        configuration.enableRelationalDb -> MySqlUserRepository(...)
        else -> InMemoryUserRepository()
    }

    // Services
    private val authService = AuthService(userRepository, integrations.clock, ...)
    private val electionService = ElectionService(electionRepository, ...)

    // HTTP Server
    private val httpServer = JettyServer(configuration.serverPort, serviceRouter, ...)

    val runner: Runnable = ApplicationRunner(httpServer, syncService, ...)

    companion object {
        fun fromConfiguration(integrations: Integrations, config: Configuration): ApplicationDependencies {
            return ApplicationDependencies(integrations, config)
        }
    }
}
```

## Frontend Architecture (Compose for Web)

### Technology Stack
- **Compose for Web**: Kotlin-based declarative UI
- **kotlinx.serialization**: JSON handling
- **Ktor Client**: HTTP API calls
- **Kotlin Coroutines**: Async operations

### UI Components (Fresh Design)
**Pages:**
- `LoginPage`: Email/password or magic link
- `RegisterPage`: New user signup
- `DashboardPage`: Election list, user info
- `ElectionDetailPage`: View election, manage candidates, vote
- `ElectionCreatePage`: Create new election
- `AdminPage`: User management, role assignment, debug views
- `UserProfilePage`: Update email, change password

**Reusable Components:**
- `Header`: Navigation, user menu
- `Button`, `TextField`, `Card`, `Dialog`: Basic UI primitives
- `ElectionCard`: Summary tile
- `RankingInput`: Drag-and-drop candidate ranking
- `TallyDisplay`: Results visualization

### State Management
**Simple approach (no Redux equivalent needed initially):**
- `StateFlow` for reactive state
- Repository pattern for data fetching
- Compose recomposition handles UI updates

```kotlin
class ElectionViewModel(private val api: ApiClient) {
    private val _elections = MutableStateFlow<List<ElectionSummary>>(emptyList())
    val elections: StateFlow<List<ElectionSummary>> = _elections.asStateFlow()

    suspend fun loadElections() {
        _elections.value = api.listElections()
    }
}
```

### API Client (Shared Contract)
```kotlin
// shared/contract/commonMain - DTOs defined once
data class LoginRequest(val nameOrEmail: String, val password: String)
data class TokenResponse(val accessToken: String, val refreshToken: String)

// frontend/jsMain/api
class ApiClient(private val baseUrl: String) {
    suspend fun login(request: LoginRequest): TokenResponse {
        return httpClient.post("$baseUrl/authenticate") {
            setBody(request)
        }.body()
    }
    // ... all other endpoints
}
```

## Deployment Architecture

### Local Development Mode
```kotlin
// local/jvmMain/runner
class LocalRunner(integrations: LocalIntegrations) : Runnable {
    private val embeddedDb = H2Database() // or testcontainers MySQL
    private val backendServer = BackendServer(port = 8080, db = embeddedDb)
    private val frontendServer = DevServer(port = 3000, proxy = "http://localhost:8080")

    override fun run() {
        embeddedDb.start()
        backendServer.start()
        frontendServer.start()
        println("App running at http://localhost:3000")
    }
}

// Execute via: ./gradlew :local:run
```

### AWS Deployment (Containerized)

#### Option A: ECS Fargate + ALB
```
                          ┌─────────────┐
                          │  Route 53   │
                          │   (DNS)     │
                          └──────┬──────┘
                                 │
                          ┌──────▼──────┐
                          │     ALB     │
                          │ (SSL Term)  │
                          └─┬─────────┬─┘
                            │         │
              ┌─────────────▼──┐   ┌──▼─────────────┐
              │  ECS Service   │   │  ECS Service   │
              │   (Backend)    │   │   (Frontend)   │
              │  Fargate Task  │   │  Fargate Task  │
              └───────┬────────┘   └────────────────┘
                      │
          ┌───────────┼───────────┐
          │           │           │
   ┌──────▼─────┐ ┌──▼────────┐ ┌▼────────────┐
   │ RDS MySQL  │ │  DynamoDB │ │ S3/Kinesis  │
   │ (Optional) │ │ (Primary) │ │ (Event Log) │
   └────────────┘ └───────────┘ └─────────────┘
```

**Components:**
- **ALB**: Application Load Balancer for HTTPS termination, routing
- **ECS Fargate**: Serverless containers for backend + frontend (Nginx-served static)
- **RDS**: Optional relational database for query-heavy operations
- **DynamoDB**: Primary data store, updated from event log
- **S3 or Kinesis**: Event log storage

#### Option B: Lambda + API Gateway (Serverless)
```
              ┌───────────────┐
              │ CloudFront    │
              │ (CDN + SSL)   │
              └─┬───────────┬─┘
                │           │
     ┌──────────▼───┐   ┌───▼──────────────┐
     │  S3 Bucket   │   │  API Gateway     │
     │  (Frontend)  │   │  HTTP API        │
     └──────────────┘   └─────────┬────────┘
                                  │
                           ┌──────▼─────────┐
                           │ Lambda Function│
                           │   (Backend)    │
                           └──┬─────────┬───┘
                              │         │
                   ┌──────────▼──┐ ┌───▼────────┐
                   │  DynamoDB   │ │ Kinesis    │
                   │  (Data)     │ │ (Events)   │
                   └─────────────┘ └────────────┘
```

**Trade-offs:**
- **Fargate**: Easier migration (Jetty runs as-is), always-on latency, predictable billing
- **Lambda**: True serverless, cold starts, pay-per-request, DynamoDB required

**Recommendation**: Start with Fargate for simpler migration, consider Lambda later

### Docker Containers

**Backend Dockerfile:**
```dockerfile
FROM eclipse-temurin:17-jre-alpine
COPY backend-all.jar /app/backend.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/backend.jar"]
```

**Frontend Dockerfile:**
```dockerfile
FROM nginx:alpine
COPY frontend/build/ /usr/share/nginx/html/
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
```

### CDK Deployment Code (Kotlin)
```kotlin
class VoteAppStack(scope: Construct, id: String) : Stack(scope, id) {
    private val vpc = Vpc(this, "VoteVpc", VpcProps.builder().maxAzs(2).build())

    private val cluster = Cluster(this, "VoteCluster", ClusterProps.builder()
        .vpc(vpc)
        .build())

    private val backendTask = createBackendService()
    private val frontendTask = createFrontendService()

    private val alb = createLoadBalancer()
    private val dynamoTable = createDynamoDbTable()
    private val eventLog = createEventLog()

    // Wire everything together...
}
```

## Testing Strategy

### Regression Test (Single Happy Path)
```kotlin
// test/commonTest/integration
class RegressionTest {
    @Test
    fun fullApplicationHappyPath() {
        // Setup: Start app with TestIntegrations
        val app = startApp(TestIntegrations)

        // User Journey
        val adminTokens = app.register("admin", "admin@test.com", "password")
        app.createElection(adminTokens.accessToken, "Best Language")
        app.setCandidates(adminTokens.accessToken, "Best Language", listOf("Kotlin", "Rust", "Go"))

        val voter1Tokens = app.register("voter1", "v1@test.com", "password")
        val voter2Tokens = app.register("voter2", "v2@test.com", "password")

        app.setEligibleVoters(adminTokens.accessToken, "Best Language", listOf("voter1", "voter2"))
        app.launchElection(adminTokens.accessToken, "Best Language", allowEdit = false)

        app.castBallot(voter1Tokens.accessToken, "voter1", "Best Language",
            listOf(Ranking(1, "Kotlin"), Ranking(2, "Rust"), Ranking(3, "Go")))
        app.castBallot(voter2Tokens.accessToken, "voter2", "Best Language",
            listOf(Ranking(1, "Rust"), Ranking(2, "Kotlin"), Ranking(3, "Go")))

        app.finalizeElection(adminTokens.accessToken, "Best Language")
        val tally = app.tally(adminTokens.accessToken, "Best Language")

        // Verify results
        assert(tally.winner == "Kotlin" || tally.winner == "Rust") // Condorcet might not have clear winner

        // Cleanup: Delete everything
        app.deleteElection(adminTokens.accessToken, "Best Language")
        app.removeUser(adminTokens.accessToken, "voter1")
        app.removeUser(adminTokens.accessToken, "voter2")
        app.removeUser(adminTokens.accessToken, "admin")

        // Verify empty state
        assert(app.userCount(adminTokens.accessToken) == 0)
        assert(app.electionCount(adminTokens.accessToken) == 0)
    }
}
```

### Edge Testing (Future Exploration)
- Unit tests for domain logic (Condorcet algorithm, ranking validation)
- Property-based tests for invariants
- Fuzzing for input validation
- All tests against public API only (implementation-agnostic)

## Migration Path Summary

1. **Setup Gradle Multiplatform Project**
2. **Migrate Shared Domain** (contracts, domain logic)
3. **Migrate Backend** (service implementation, add DynamoDB support)
4. **Implement Event Log** (choose technology, implement projections)
5. **Implement Compose for Web Frontend** (fresh UI, shared contracts)
6. **Implement Local Runner** (embedded DB, single-command startup)
7. **Containerize Applications** (Docker for backend/frontend)
8. **Implement CDK Deployment** (Fargate + ALB + DynamoDB)
9. **Implement Regression Test** (validate full behavior)
10. **Deprecate Old Repositories** (archive condorcet-backend/frontend/deploy)
