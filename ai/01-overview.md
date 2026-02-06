# Project Overview: Condorcet Voting System

## Goal
Merge three separate projects into a single Kotlin multiplatform application with Compose for Web frontend, deployable both locally and to AWS.

## Source Projects
- **Backend**: `/Users/seashubi/github.com/SeanShubin/condorcet-backend`
- **Frontend**: `/Users/seashubi/github.com/SeanShubin/condorcet-frontend`
- **Deployment**: `/Users/seashubi/github.com/SeanShubin/condorcet-deploy`

## Core Requirements

### Deployment Flexibility
- Run entire application locally without cloud deployment
- Deploy to public URL via Amazon Web Services
- Consider containerization for deployment

### Backend Requirements
- Preserve all existing behavior
- Add DynamoDB support alongside existing relational database
- Hide database differences behind appropriate abstractions
- Event sourcing architecture:
  - Event log as source of truth (efficient for fast writes)
  - Regenerate both relational and DynamoDB from event log
  - Event log data must be structured and highly durable
- Create prioritized access pattern list for DynamoDB tuning

### Frontend Requirements
- Preserve all existing capabilities
- Reimplement using idiomatic Compose for Web patterns
- Fresh design and styling (do not preserve previous UI design)
- Focus on capability, not implementation preservation

### Feature Completeness
- Any entity that can be added can also be removed
- Support complete reset: remove all elections, all other users, finally self
- Full CRUD operations for all entities

### Testing Strategy
- Single regression test exercising happy path for each feature
- Exploring approach: many fast, specific tests that:
  - Verify only from outermost application edges
  - Check every logic path
  - Allow implementation details to change freely
  - Require only behavior preservation

## Open Questions

### Architecture
- [ ] Module structure (backend, frontend, shared, domain, deploy)?
- [ ] Package organization (domain-first per coding standards)?
- [ ] Shared business logic location?

### Technology Stack
- [ ] Backend framework? (current unknown)
- [ ] Database? (current unknown)
- [ ] Event log technology? (file system, Kafka, DynamoDB Streams, other?)
- [ ] Frontend framework currently? (to be replaced with Compose for Web)
- [ ] Build system details?

### AWS Architecture
- [ ] Compute: ECS/Fargate? Lambda? EC2? Elastic Beanstalk?
- [ ] Storage: S3 for static assets?
- [ ] Database: RDS? DynamoDB table design?
- [ ] Networking: Load balancer? CloudFront CDN?

### Authentication & API
- [ ] Current auth mechanism?
- [ ] API style: REST? GraphQL? gRPC?
- [ ] Shared DTOs in multiplatform module?
- [ ] Serialization approach?

### Dependency Injection
- [ ] DI framework choice? (Koin? Manual composition roots?)
- [ ] Integrations/Bootstrap/Application stage structure per coding standards?

### Data Model
- [ ] Current database schema?
- [ ] Core entities beyond User and Election?
- [ ] Relationships and constraints?

### DynamoDB Access Patterns
- [ ] User login/lookup?
- [ ] Election creation/retrieval?
- [ ] Vote recording/tallying?
- [ ] Other key queries?

## Next Steps
1. Analyze existing projects to answer open questions
2. Document current state (architecture, tech stack, data model)
3. Define target architecture
4. Create migration plan with priorities
5. Document specific technical decisions
