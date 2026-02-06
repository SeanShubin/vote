# Current State Analysis

## condorcet-backend

### Technology Stack
- **Language**: Kotlin 1.7.20
- **Build System**: Maven (multi-module)
- **Runtime**: JDK 12
- **Web Server**: Jetty 11.0.12 with HTTP/2 support
- **Database**: MySQL 8.0.30
- **JSON**: Jackson 2.13.4 with Kotlin module
- **Authentication**: JWT via Auth0 java-jwt 4.1.0
- **Email**: javax.mail 1.6.2
- **Functional Programming**: Arrow-kt 1.1.3
- **Concurrency**: kotlinx-coroutines 1.6.4

### Project Structure (Multi-Module Maven)
```
condorcet-backend/
├── console                    # Application entry point
├── server                     # Jetty HTTP server
├── service                    # Core business logic interface
├── service-http               # HTTP endpoint adapters
├── domain                     # Domain entities and logic
├── database                   # SQL queries and data access
├── genericdb                  # Generic database abstractions
├── jwt                        # JWT token handling
├── crypto                     # Password hashing/salting
├── http                       # HTTP utilities
├── mail                       # Email sending
├── logger                     # Logging infrastructure
├── json                       # JSON serialization
├── io                         # I/O utilities
├── contract                   # File system abstractions
├── string-util                # String utilities
├── configuration-util         # Configuration management
├── global-constants           # Shared constants
├── dependencies               # Dependency wiring
└── prototype                  # Experimental code
```

### Domain Model
**Core Entities:**
- `User`: userName, email, password (salted/hashed), role
- `Election`: name, owner, status (draft/launched/finalized), voting rules
- `Candidate`: name, election association
- `Ballot`: voter, election, rankings, confirmation ID
- `Ranking`: candidate rankings with preference order

**Value Objects:**
- `AccessToken`, `RefreshToken`: JWT-based authentication
- `Role`: Admin, Observer, Voter
- `Permission`: Fine-grained authorization
- `Tally`: Election results using Condorcet method
- `VoterEligibility`: Who can vote in each election

### API Surface (Service Interface)

**Authentication:**
- `register(userName, email, password): Tokens`
- `authenticate(nameOrEmail, password): Tokens`
- `authenticateWithToken(accessToken): Tokens`
- `refresh(refreshToken): Tokens`
- `sendLoginLinkByEmail(email, baseUri)`
- `changePassword(accessToken, userName, password)`

**User Management:**
- `listUsers(accessToken): List<UserNameRole>`
- `getUser(accessToken, userName): UserNameEmail`
- `updateUser(accessToken, userName, userUpdates)`
- `removeUser(accessToken, userName)`
- `setRole(accessToken, userName, role)`

**Election Management:**
- `addElection(accessToken, userName, electionName)`
- `listElections(accessToken): List<ElectionSummary>`
- `getElection(accessToken, electionName): ElectionDetail`
- `updateElection(accessToken, electionName, electionUpdates)`
- `deleteElection(accessToken, electionName)`
- `launchElection(accessToken, electionName, allowEdit)`
- `finalizeElection(accessToken, electionName)`

**Candidate Management:**
- `setCandidates(accessToken, electionName, candidateNames)`
- `listCandidates(accessToken, electionName): List<String>`

**Voting:**
- `setEligibleVoters(accessToken, electionName, voterNames)`
- `listEligibility(accessToken, electionName): List<VoterEligibility>`
- `isEligible(accessToken, userName, electionName): Boolean`
- `castBallot(accessToken, voterName, electionName, rankings)`
- `listRankings(accessToken, voterName, electionName): List<Ranking>`
- `getBallot(accessToken, voterName, electionName): BallotSummary?`
- `tally(accessToken, electionName): Tally`

**Admin/Debug:**
- `health(): String`
- `synchronize()` (event log sync)
- `listTables(accessToken): List<String>`
- `tableData(accessToken, tableName): TableData`
- `debugTableData(accessToken, tableName): TableData`
- `eventData(accessToken): TableData`
- `userCount/electionCount/tableCount/eventCount(accessToken): Int`

### Event Sourcing Architecture
- **Event Log**: Append-only event store (table-based)
- **Synchronization**: `synchronize()` rebuilds relational tables from events
- **Read Models**: MySQL tables materialized from events
- **Write Path**: Commands → Events → Read Model Sync

### Database Schema (Inferred from SQL files)
**Core Tables:**
- `user`: userName (PK), email, salt, hash, role
- `election`: name (PK), ownerName (FK), status, voting rules
- `candidate`: electionName (FK), candidateName
- `ballot`: voterName (FK), electionName (FK), rankings, confirmation ID
- `voter_eligibility`: electionName (FK), voterName (FK)
- `event`: event log for event sourcing
- `variable`: system state (e.g., last_synced timestamp)

### Dependency Injection Pattern
- **Manual Composition**: `dependencies` module wires everything
- **Follows Sean's standards**: Constructor injection, interfaces for I/O
- **No Framework**: Pure Kotlin composition roots

## condorcet-frontend

### Technology Stack
- **Framework**: React 17.0.1
- **Build System**: Create React App (react-scripts 4.0.1)
- **State Management**: Redux 4.0.5 + Redux Saga 1.1.3
- **Functional Utilities**: Ramda 0.27.1
- **HTTP Proxy**: http-proxy-middleware 1.0.6 (for local dev)
- **Testing**: Jest + React Testing Library

### Project Structure
```
condorcet-frontend/
├── public/                    # Static assets
├── src/
│   ├── components/           # React components (inferred)
│   ├── redux/                # Redux store, actions, sagas (inferred)
│   ├── api/                  # Backend API integration (inferred)
│   └── App.js                # Root component
├── build/                    # Production build output
└── nginx.conf                # Nginx config for deployment
```

### Features (Based on Backend API)
**User Features:**
- User registration and login
- Password change
- Email-based login links
- Role management (Admin/Observer/Voter views)

**Election Features:**
- Create/edit/delete elections
- Launch elections (start voting)
- Finalize elections (end voting, lock results)
- View election list and details
- Set candidates
- Manage voter eligibility

**Voting Features:**
- Cast ranked-choice ballots
- View own ballot
- View election results (Condorcet tally)
- See voter eligibility status

**Admin Features:**
- User management
- Role assignment
- System health monitoring
- Debug views for tables and events

### State Management
- **Redux Store**: Centralized application state
- **Sagas**: Async side effects (API calls)
- **Ramda**: Functional transformations of state

## condorcet-deploy

### Technology Stack
- **Infrastructure as Code**: AWS CDK 2.13.0 (Kotlin 1.6.10, JDK 17)
- **Build System**: Maven (modules: console, domain, json, contract)
- **Cloud Provider**: Amazon Web Services

### Deployment Architecture (Inferred from README)
**AWS Services:**
- **Compute**: EC2 instances (via CDK constructs)
- **Database**: RDS MySQL (with RemovalPolicy.RETAIN)
- **Email**: Amazon SES (SMTP credentials)
- **Networking**: VPC, Security Groups, potentially API Gateway v2
- **Domain/SSL**: Manual certificate creation, domain registration

**Regions:**
- Primary: us-east-1
- Secondary: us-west-1 (cross-region setup planned)

### Manual Setup Requirements
- AWS Account Configuration (964638509728)
- CDK Bootstrap
- SSH Keypair (CondorcetKey)
- Domain Registration
- SSL Certificate Creation
- SES Email Verification
- SMTP Credentials

### Deployment Scripts
- `create.sh`: Provision infrastructure and deploy application
- `destroy.sh`: Tear down non-data infrastructure (preserve database)
- `login.sh`: AWS credential management
- `backup.sh`: Database backup (planned)
- `restore.sh`: Database restore (planned)
- `purge`: Remove database (planned)

### Current Limitations
- Some manual steps required (certificates, domains, SES)
- Cross-region setup incomplete
- Backup/restore not yet implemented
- Password reset feature not implemented in deployment

## Integration Points

### Backend ↔ Frontend
- **Protocol**: HTTP/HTTPS (Jetty server)
- **Format**: JSON (Jackson on backend, standard fetch on frontend)
- **Authentication**: JWT (accessToken in Authorization header, refreshToken in cookies/storage)
- **CORS**: Required for local development

### Backend ↔ Database
- **Driver**: MySQL Connector/J 8.0.30
- **Query Management**: SQL files in resources, loaded at runtime
- **Connection Pooling**: (not specified, likely default)

### Deployment ↔ Application
- **Backend Deployment**: JAR packaging, EC2 execution
- **Frontend Deployment**: Static build, Nginx serving
- **Database Deployment**: RDS MySQL provisioning

## Architecture Strengths
1. **Clean Module Separation**: Backend modules well-organized by concern
2. **Event Sourcing**: Solid foundation for audit trail and data recovery
3. **Type Safety**: Kotlin provides strong typing throughout
4. **Manual DI**: Follows Sean's coding standards, testable architecture
5. **Comprehensive API**: Full CRUD + domain-specific operations

## Architecture Challenges
1. **Multi-Project Coordination**: Three separate repos, manual integration
2. **Technology Fragmentation**: Maven (backend), npm (frontend), CDK (deploy)
3. **No Shared Types**: Frontend/backend data contracts maintained separately
4. **Manual Deployment Steps**: Certificate, domain, SES setup
5. **Event Log Implementation**: Table-based, may not scale for high write throughput
6. **No DynamoDB**: Only MySQL, limited AWS-native integration
7. **Frontend Rebuild**: React → Compose for Web is complete rewrite

## Missing/Incomplete Features
1. **Deletion Support**: Some entities may lack delete operations
2. **Backup/Restore**: Planned but not implemented
3. **Password Reset via Email**: Not in deployment yet
4. **Cross-Region Deployment**: Planned but incomplete
5. **Auto-scaling**: Not configured
6. **Monitoring/Alerting**: No CloudWatch integration specified
7. **CI/CD Pipeline**: Manual deployment scripts only
