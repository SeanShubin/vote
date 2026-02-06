# Vote Project Planning Documentation

This directory contains comprehensive planning documents for migrating the Condorcet voting system from three separate projects into a single Kotlin Multiplatform application.

## Document Overview

### 01-overview.md
**Start here.** High-level project goals, constraints, and open questions.
- Merger of condorcet-backend, condorcet-frontend, condorcet-deploy
- Key requirements: local + AWS deployment, dual database support, event sourcing
- Feature completeness requirements (full CRUD, reset capability)
- Testing strategy

### 02-current-state.md
Detailed analysis of the three existing projects.
- Technology stack inventory (Kotlin, React, AWS CDK)
- Project structures and module organization
- Domain model and API surface
- Event sourcing implementation
- Integration points
- Identified strengths and challenges

### 03-target-architecture.md
The vision: what we're building.
- Kotlin Multiplatform project structure
- Backend with relational + DynamoDB support
- Compose for Web frontend
- Event log as source of truth
- Dependency injection (Integrations/Bootstrap/Application stages)
- Local development mode (single command startup)
- AWS deployment (Docker + ECS Fargate + ALB)
- Testing strategy (single regression test)

### 04-technical-decisions.md
Key technology choices with options, trade-offs, and recommendations.
- Event log technology (DynamoDB Streams recommended)
- DynamoDB table design (multi-table recommended)
- Build system (Gradle Kotlin DSL required for Compose)
- Local database (DynamoDB Local + H2)
- AWS compute (ECS Fargate recommended over Lambda)
- Frontend state management (StateFlow + ViewModels)
- API design (REST, preserve current)
- Dependency injection (manual composition, preserve current)
- Authentication storage (LocalStorage MVP → HttpOnly cookies later)
- Deployment tooling (AWS CDK, preserve current)

### 05-migration-plan.md
Step-by-step implementation plan with timeline and milestones.
- **Phase 1**: Foundation (project setup, shared domain) - Weeks 1-2
- **Phase 2**: Backend migration (multi-DB support) - Weeks 3-5
- **Phase 3**: Frontend implementation (Compose for Web) - Weeks 6-8
- **Phase 4**: Local development experience - Week 9
- **Phase 5**: Deployment (Docker + CDK) - Weeks 10-11
- **Phase 6**: Testing & quality - Week 12
- **Phase 7**: Documentation & handoff - Week 13

**Total timeline**: ~13 weeks (3 months)

## How to Use These Documents

### For Planning
1. Read **01-overview.md** to understand goals
2. Review **04-technical-decisions.md** and approve/adjust recommendations
3. Review **05-migration-plan.md** and adjust timeline/priorities

### For Implementation
1. Follow **05-migration-plan.md** phase by phase
2. Reference **03-target-architecture.md** for design details
3. Reference **04-technical-decisions.md** when making tech choices
4. Reference **02-current-state.md** when migrating existing code

### For Review
1. Check **01-overview.md** for requirements coverage
2. Check **03-target-architecture.md** for design quality
3. Check **05-migration-plan.md** for completeness and risk management

## Next Steps

1. **Review all documents** with stakeholders
2. **Approve technical decisions** or adjust recommendations
3. **Kick off Phase 1** (project setup and shared domain migration)
4. **Iterate** through migration plan, updating documents as needed

## Document Maintenance

These documents are living artifacts:
- Update as you learn and make decisions
- Track changes to technical decisions (with rationale)
- Adjust migration plan based on actual progress
- Document deviations from the plan

## Questions or Clarifications

If something is unclear or needs more detail:
- Add questions to **01-overview.md** (Open Questions section)
- Propose new technical decisions in **04-technical-decisions.md**
- Adjust migration plan tasks in **05-migration-plan.md**

## Source Projects

- **Backend**: `/Users/seashubi/github.com/SeanShubin/condorcet-backend`
- **Frontend**: `/Users/seashubi/github.com/SeanShubin/condorcet-frontend`
- **Deploy**: `/Users/seashubi/github.com/SeanShubin/condorcet-deploy`

These will be archived after successful migration.
