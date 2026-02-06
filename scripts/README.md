# Project Scripts

This directory contains all common project operations as executable scripts.
Discover what you can do by browsing the filenames!

## Quick Reference

### Building & Testing
- `./scripts/build` - Build entire project
- `./scripts/test` - Run all tests
- `./scripts/verify` - Quick verification (build + test)
- `./scripts/clean` - Clean all build artifacts

### Running Locally
- `./scripts/run-local` - Start complete local environment (backend + frontend + databases)
- `./scripts/run-backend` - Start backend server only
- `./scripts/run-frontend` - Start frontend dev server only

### Database Management
- `./scripts/db-setup` - Start local databases (DynamoDB Local, H2)
- `./scripts/db-teardown` - Stop local databases
- `./scripts/db-reset` - Reset databases to clean state

### Development
- `./scripts/watch-frontend` - Frontend development with hot reload
- `./scripts/format` - Format all code (when formatter configured)
- `./scripts/lint` - Lint all code (when linter configured)

### Utilities
- `./scripts/check-java` - Verify Java version
- `./scripts/generate-wrapper` - Regenerate Gradle wrapper

## Requirements

- Java 21 (managed via asdf - see `.tool-versions`)
- Docker (for local databases)

## Notes

All scripts use the Gradle wrapper (`./gradlew`) and respect the asdf Java configuration.
