# Migration Plan

## Overview

This document outlines the step-by-step process to migrate from three separate projects (condorcet-backend, condorcet-frontend, condorcet-deploy) to a single Kotlin Multiplatform project with Compose for Web frontend.

## Phases

### Phase 1: Foundation (Weeks 1-2)
**Goal**: Set up project structure and shared code

#### 1.1 Project Setup
- [ ] Create Gradle Kotlin Multiplatform project structure
- [ ] Configure subprojects: `shared`, `backend`, `frontend`, `deploy`, `local`, `test`
- [ ] Set up Gradle build scripts (Kotlin DSL)
- [ ] Configure Kotlin versions, multiplatform targets (JVM, JS)
- [ ] Add Compose Multiplatform plugin for `frontend` module
- [ ] Set up CI/CD skeleton (GitHub Actions or similar)

**Deliverable**: Empty project that builds successfully with `./gradlew build`

#### 1.2 Shared Domain Migration
- [ ] Create `shared/domain/commonMain` module
- [ ] Copy domain entities from `condorcet-backend/domain`:
  - User, Election, Candidate, Ballot, Ranking, Tally
  - Role, Permission, VoterEligibility
  - All value objects (AccessToken, RefreshToken, etc.)
- [ ] Copy domain logic:
  - Condorcet algorithm (TallyCalculator)
  - Ranking validation
  - Permission checking
- [ ] Add unit tests for domain logic in `shared/domain/commonTest`
- [ ] Verify tests pass: `./gradlew :shared:domain:test`

**Deliverable**: Shared domain module with passing tests

#### 1.3 Shared Contract (API DTOs)
- [ ] Create `shared/contract/commonMain` module
- [ ] Define all API request/response DTOs:
  - LoginRequest, TokenResponse
  - CreateElectionRequest, ElectionResponse
  - CastBallotRequest, BallotResponse
  - (All DTOs matching current Service interface)
- [ ] Add kotlinx.serialization annotations
- [ ] Generate/validate JSON serialization in tests
- [ ] Document API contract in README

**Deliverable**: Shared contract module with serializable DTOs

**Milestone**: Foundation complete - shared code compiles and tests pass

---

### Phase 2: Backend Migration (Weeks 3-5)
**Goal**: Migrate backend service with multi-database support

#### 2.1 Service Interface Migration
- [ ] Create `backend/service` package
- [ ] Copy `Service` interface from condorcet-backend
- [ ] Adapt to use shared domain types
- [ ] Create ServiceImpl (initially stubbed)
- [ ] Set up dependency injection structure (Integrations/Bootstrap/Application)

#### 2.2 Database Abstraction Layer
- [ ] Create repository interfaces in `backend/database`:
  - UserRepository, ElectionRepository, CandidateRepository, BallotRepository, EligibilityRepository
- [ ] Define common methods (find, save, delete, list)
- [ ] Use suspend functions for async operations

#### 2.3 MySQL Implementation
- [ ] Migrate SQL queries from `condorcet-backend/database/src/main/resources`
- [ ] Implement MySqlUserRepository, MySqlElectionRepository, etc.
- [ ] Copy JDBC connection logic
- [ ] Implement query loading (ResourceLoader pattern)
- [ ] Add connection pooling (HikariCP)
- [ ] Test against real MySQL (testcontainers)

#### 2.4 Event Log Implementation
- [ ] Create EventStore interface
- [ ] Define DomainEvent sealed class hierarchy
- [ ] Implement DynamoDbEventStore:
  - Table: CondorcetEvents (eventId, timestamp, eventType, payload)
  - Append-only writes
  - Query by aggregateId
- [ ] Implement event serialization (kotlinx.serialization)
- [ ] Test with DynamoDB Local

#### 2.5 DynamoDB Implementation
- [ ] Design DynamoDB tables (see Technical Decisions doc)
- [ ] Implement DynamoDbUserRepository, DynamoDbElectionRepository, etc.
- [ ] Add DynamoDB SDK dependency (AWS SDK for Kotlin)
- [ ] Implement all access patterns
- [ ] Test with DynamoDB Local

#### 2.6 Projection/Synchronization Service
- [ ] Create SyncService that reads events and updates projections
- [ ] Implement event handlers for each event type
- [ ] Support both MySQL and DynamoDB projections
- [ ] Add checkpoint/cursor management (track last processed event)
- [ ] Test: write event → verify projections updated

#### 2.7 HTTP Server Migration
- [ ] Copy Jetty server setup from `condorcet-backend/server`
- [ ] Create HTTP route handlers (map Service methods to endpoints)
- [ ] Implement JWT authentication middleware
- [ ] Add CORS configuration
- [ ] Add request/response logging
- [ ] Implement health check endpoint

#### 2.8 Supporting Services
- [ ] Crypto: Copy password hashing/salting (bcrypt or similar)
- [ ] JWT: Copy token generation/validation (Auth0 java-jwt)
- [ ] Email: Copy email sending (javax.mail or AWS SES SDK)
- [ ] Configuration: Implement config loading (HOCON, JSON, or env vars)

#### 2.9 Integration Tests
- [ ] Test full service stack with H2 in-memory database
- [ ] Test authentication flow (register, login, refresh)
- [ ] Test election CRUD operations
- [ ] Test voting flow (eligibility, cast ballot, tally)
- [ ] Test authorization (admin vs voter permissions)

**Deliverable**: Backend module with passing integration tests

**Milestone**: Backend complete - can run locally with H2, tests pass

---

### Phase 3: Frontend Implementation (Weeks 6-8)
**Goal**: Build new Compose for Web UI

#### 3.1 Compose for Web Setup
- [ ] Configure Compose for Web in `frontend` module
- [ ] Set up dev server with hot reload
- [ ] Create basic HTML entry point (index.html)
- [ ] Implement main App composable
- [ ] Verify browser loads and displays "Hello World"

#### 3.2 API Client
- [ ] Implement ApiClient using Ktor Client (or kotlinx-browser fetch)
- [ ] Use shared contract DTOs for requests/responses
- [ ] Add JWT token management (store in memory initially)
- [ ] Implement token refresh logic
- [ ] Add error handling and retry logic
- [ ] Test against backend running on localhost:8080

#### 3.3 Design System / Theme
- [ ] Define color palette, typography, spacing
- [ ] Create reusable UI components:
  - Button, TextField, Card, Dialog, Modal
  - Layout components (Header, Footer, Container)
- [ ] Implement responsive design (desktop/mobile)
- [ ] Add loading spinners, error messages

#### 3.4 Authentication Pages
- [ ] LoginPage: email/password form
- [ ] RegisterPage: username, email, password form
- [ ] Token storage (LocalStorage initially)
- [ ] Redirect to dashboard on successful auth
- [ ] "Forgot password" placeholder (email link flow)

#### 3.5 Dashboard Page
- [ ] Display user info (name, email, role)
- [ ] List user's elections (created by user)
- [ ] List elections user can vote in
- [ ] "Create Election" button → CreateElectionPage
- [ ] "Logout" button

#### 3.6 Election Pages
- [ ] ElectionDetailPage:
  - Show election name, owner, status
  - List candidates
  - Show eligibility (if voter)
  - "Cast Ballot" button (if eligible and launched)
  - "View Results" button (if finalized)
- [ ] CreateElectionPage:
  - Form: election name
  - Add candidates (dynamic list input)
  - Set eligible voters (multiselect)
  - "Create" button
- [ ] EditElectionPage (if not launched):
  - Update candidates
  - Update eligible voters
  - "Launch Election" button

#### 3.7 Voting Page
- [ ] CastBallotPage:
  - Display candidates
  - Ranking input (drag-and-drop or numbered inputs)
  - "Submit Ballot" button
  - Confirmation message
- [ ] ViewBallotPage:
  - Show user's submitted rankings
  - (No edit if election finalized)

#### 3.8 Results Page
- [ ] TallyPage:
  - Display Condorcet winner
  - Show pairwise comparison matrix
  - List all ballots (if election settings allow)
  - Visualize results (table or chart)

#### 3.9 Admin Pages (if user has admin role)
- [ ] UserManagementPage:
  - List all users
  - Assign roles (admin, observer, voter)
  - Delete users
- [ ] DebugPage:
  - View database tables
  - View event log
  - System health info

#### 3.10 State Management
- [ ] Implement ViewModels with StateFlow for each page
- [ ] Handle loading/error states
- [ ] Implement navigation (routing between pages)
- [ ] Add optimistic UI updates where appropriate

**Deliverable**: Frontend module with full UI implementation

**Milestone**: Frontend complete - can interact with backend, full feature parity

---

### Phase 4: Local Development Experience (Week 9)
**Goal**: Single-command local startup

#### 4.1 Local Module Implementation
- [ ] Create `local/jvmMain` module
- [ ] Implement LocalIntegrations (DynamoDB Local, in-memory email, fixed clock for testing)
- [ ] Create embedded database setup (DynamoDB Local via Docker or embedded lib)
- [ ] Implement LocalRunner:
  - Start DynamoDB Local
  - Start backend server on :8080
  - Start frontend dev server on :3000 (with proxy to :8080)
  - Wait for shutdown signal
- [ ] Add Gradle task: `./gradlew :local:run`
- [ ] Test full local flow

#### 4.2 Sample Data Seeding
- [ ] Create seed script that populates sample data:
  - Admin user (username: admin, password: admin)
  - Sample election ("Favorite Language")
  - Sample candidates (Kotlin, Rust, Go)
  - Sample voters
- [ ] Run seed script on first startup
- [ ] Document in README

**Deliverable**: `./gradlew :local:run` starts full application locally

**Milestone**: Local development complete - frictionless dev experience

---

### Phase 5: Deployment (Weeks 10-11)
**Goal**: Deploy to AWS with Docker + CDK

#### 5.1 Docker Containerization
- [ ] Create Dockerfile for backend:
  - Use eclipse-temurin:17-jre-alpine
  - Copy fat JAR
  - Expose port 8080
- [ ] Create Dockerfile for frontend:
  - Use nginx:alpine
  - Copy compiled JS/WASM output
  - Configure nginx.conf for SPA routing
  - Expose port 80
- [ ] Test containers locally with docker-compose
- [ ] Push images to ECR (AWS Container Registry)

#### 5.2 CDK Infrastructure
- [ ] Migrate existing CDK code from condorcet-deploy
- [ ] Create VPC, subnets, security groups
- [ ] Create ECS Fargate cluster
- [ ] Create ECS task definitions (backend, frontend)
- [ ] Create ECS services (backend, frontend)
- [ ] Create Application Load Balancer:
  - Route `/api/*` to backend service
  - Route `/*` to frontend service
- [ ] Create DynamoDB tables (Events, Users, Elections, Candidates, Ballots, Eligibility)
- [ ] Create RDS MySQL (optional for hybrid mode)
- [ ] Create S3 bucket for static assets (if needed)
- [ ] Configure SSL certificate (ACM)
- [ ] Configure Route 53 DNS (if custom domain)

#### 5.3 Environment Configuration
- [ ] Create configuration for production:
  - DynamoDB table names
  - RDS connection string (if used)
  - JWT secret (from Secrets Manager)
  - Email credentials (SES or SMTP from Secrets Manager)
- [ ] Pass config to ECS tasks via environment variables
- [ ] Test configuration loading

#### 5.4 Deployment Scripts
- [ ] Create `deploy.sh` script:
  - Build backend JAR: `./gradlew :backend:shadowJar`
  - Build frontend: `./gradlew :frontend:jsBrowserDistribution`
  - Build Docker images
  - Push images to ECR
  - Run CDK deploy: `cdk deploy`
- [ ] Create `destroy.sh` script:
  - Run CDK destroy (preserves DynamoDB tables with RemovalPolicy.RETAIN)
- [ ] Document deployment process in README

#### 5.5 Smoke Tests
- [ ] Deploy to AWS
- [ ] Hit health endpoint: `curl https://your-domain.com/api/health`
- [ ] Open frontend: `https://your-domain.com`
- [ ] Register a user
- [ ] Create an election
- [ ] Cast a ballot
- [ ] View results
- [ ] Verify DynamoDB tables populated
- [ ] Verify event log working

**Deliverable**: Application deployed to AWS and functional

**Milestone**: Deployment complete - app accessible via public URL

---

### Phase 6: Testing & Quality (Week 12)
**Goal**: Comprehensive regression test and quality checks

#### 6.1 Regression Test Implementation
- [ ] Create comprehensive regression test in `test/commonTest/integration`
- [ ] Test happy path:
  - Register admin
  - Create election
  - Set candidates
  - Register voters
  - Set eligibility
  - Launch election
  - Cast ballots
  - Finalize election
  - View tally
  - Delete election
  - Delete users
  - Verify empty state
- [ ] Run against local app: `./gradlew :test:integrationTest`
- [ ] Run against AWS deployment (optional)

#### 6.2 Feature Completeness Validation
- [ ] Verify all entities can be deleted:
  - Delete user (self-deletion)
  - Delete election
  - Delete candidates (via election update)
  - Delete ballots (via election deletion)
- [ ] Test reset to empty state:
  - Admin deletes all elections
  - Admin deletes all other users
  - Admin deletes self
  - Verify database empty
- [ ] Document any missing features

#### 6.3 Performance Testing (Optional)
- [ ] Load test backend API (Apache Bench or K6)
- [ ] Measure response times
- [ ] Identify bottlenecks
- [ ] Optimize if necessary

#### 6.4 Security Review
- [ ] Review JWT implementation (token expiry, refresh, revocation)
- [ ] Review password hashing (bcrypt or argon2)
- [ ] Review CORS configuration
- [ ] Review input validation
- [ ] Review SQL injection protection (parameterized queries)
- [ ] Review XSS protection (no innerHTML, sanitized outputs)
- [ ] Add CSP headers

**Deliverable**: Passing regression test, documented feature parity

**Milestone**: Testing complete - confidence in production readiness

---

### Phase 7: Documentation & Handoff (Week 13)
**Goal**: Complete documentation and deprecate old repositories

#### 7.1 User Documentation
- [ ] Write user guide:
  - How to register
  - How to create an election
  - How to vote
  - How to view results
- [ ] Add screenshots/videos (optional)

#### 7.2 Developer Documentation
- [ ] Document architecture (point to ai/*.md docs)
- [ ] Document local setup: `./gradlew :local:run`
- [ ] Document deployment: `./deploy.sh`
- [ ] Document database schema (both DynamoDB and MySQL)
- [ ] Document API endpoints (OpenAPI/Swagger optional)
- [ ] Document configuration options

#### 7.3 Operations Documentation
- [ ] Document monitoring (CloudWatch logs, metrics)
- [ ] Document backup/restore process
- [ ] Document rollback process
- [ ] Document debugging tips
- [ ] Create runbook for common issues

#### 7.4 Repository Cleanup
- [ ] Archive condorcet-backend repository (mark as deprecated in README)
- [ ] Archive condorcet-frontend repository
- [ ] Archive condorcet-deploy repository
- [ ] Update links to point to new repository
- [ ] Announce deprecation (if public or shared)

**Deliverable**: Complete documentation, deprecated old repos

**Milestone**: Project complete - ready for production use

---

## Risk Management

### High-Risk Items
1. **Compose for Web Maturity**: Still relatively new, may have quirks
   - Mitigation: Prototype early, keep UI simple initially
2. **DynamoDB Access Patterns**: May need optimization after real usage
   - Mitigation: Start with multi-table design (simpler), optimize later
3. **Event Log Replay Performance**: Rebuilding projections from events may be slow
   - Mitigation: Add checkpointing, incremental sync
4. **Migration Effort Underestimation**: 13 weeks may not be enough
   - Mitigation: Prioritize MVP features, defer "nice-to-haves"

### Medium-Risk Items
1. **Gradle Migration**: Team unfamiliar with Gradle
   - Mitigation: Provide training, document build scripts
2. **Docker/Container Learning Curve**: Team unfamiliar with containers
   - Mitigation: Pair programming, document Dockerfiles
3. **AWS Costs**: Could exceed budget
   - Mitigation: Monitor costs, use Fargate Spot (cheaper), optimize DynamoDB

### Low-Risk Items
1. **Backend Migration**: Mostly copy-paste with minor refactoring
2. **Shared Domain**: Clean separation, easy to extract
3. **Local Development**: Well-defined scope

---

## Success Criteria

- [ ] Single repository with all code
- [ ] `./gradlew :local:run` starts application locally
- [ ] `./deploy.sh` deploys to AWS
- [ ] Application accessible via public URL
- [ ] All existing features preserved (feature parity)
- [ ] Regression test passes
- [ ] All entities can be deleted (feature completeness)
- [ ] Can reset to empty state
- [ ] Documentation complete

---

## Timeline Summary

| Phase | Duration | Milestone |
|-------|----------|-----------|
| Phase 1: Foundation | Weeks 1-2 | Shared code compiles |
| Phase 2: Backend | Weeks 3-5 | Backend tests pass |
| Phase 3: Frontend | Weeks 6-8 | Frontend feature-complete |
| Phase 4: Local Dev | Week 9 | Single-command local startup |
| Phase 5: Deployment | Weeks 10-11 | Deployed to AWS |
| Phase 6: Testing | Week 12 | Regression test passes |
| Phase 7: Docs | Week 13 | Project complete |

**Total**: ~13 weeks (3 months)

---

## Next Steps

1. **Review and approve technical decisions** (see 04-technical-decisions.md)
2. **Set up initial project structure** (Phase 1.1)
3. **Begin shared domain migration** (Phase 1.2)
4. **Iterate through phases, adjusting plan as needed**

This plan is a living document - update as you learn and adjust priorities.
