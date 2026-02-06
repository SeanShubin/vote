# Schema Diagram Generator

Generates relational model diagrams directly from `schema.sql` at build time, ensuring documentation cannot drift from actual code.

## Overview

This module parses the MySQL schema definition and generates multiple diagram formats:

- **GraphViz (.dot)** - For rendering with Graphviz tools
- **GraphViz SVG (.svg)** - Pre-rendered diagram (if `dot` is installed)
- **Mermaid (.mmd)** - GitHub-native ER diagrams
- **HTML table** - Browsable schema documentation

**Key benefit**: Diagrams are regenerated from the actual schema file on every build, so they cannot become stale.

## Usage

### Generate Diagrams

```bash
./gradlew :schema-diagram:generateSchemaDiagram
```

Output files are written to `generated/schema-diagram/`:
- `schema.dot` - GraphViz source
- `schema.svg` - Rendered SVG diagram (requires GraphViz installed)
- `schema.mmd` - Mermaid ER diagram
- `schema.html` - HTML table view with embedded SVG diagram at the top

### Automatic Generation

The `generateSchemaDiagram` task runs automatically as part of `:schema-diagram:build`, so diagrams are regenerated whenever you build the project.

## What It Captures

From `backend/src/main/resources/database/schema.sql`, the parser extracts:

1. **Tables** - All CREATE TABLE statements
2. **Columns** - Name, type, NOT NULL constraint
3. **Primary Keys** - Both inline and explicit PRIMARY KEY constraints
4. **Foreign Keys** - Relationships with ON DELETE actions
5. **Composite Keys** - Multi-column primary keys

The parser skips MySQL-specific syntax (ENGINE, CHARSET, INDEX definitions) to focus on the relational model.

## Example Output

### Mermaid ER Diagram

```mermaid
erDiagram
    users ||--o{ elections : "owner_name"
    elections ||--o{ candidates : "election_name"
    elections ||--o{ ballots : "election_name"
    users ||--o{ ballots : "voter_name"

    users {
        VARCHAR(255) name PK
        VARCHAR(255) email NOT_NULL
        VARCHAR(255) salt NOT_NULL
        VARCHAR(255) hash NOT_NULL
        VARCHAR(50) role NOT_NULL
    }
    elections {
        VARCHAR(255) election_name PK
        VARCHAR(255) owner_name FK NOT_NULL
        BOOLEAN secret_ballot
        TIMESTAMP no_voting_before
        TIMESTAMP no_voting_after
        BOOLEAN allow_edit
        BOOLEAN allow_vote
    }
```

### GraphViz (Rendered)

The `.svg` file shows boxes with tables and their columns, with arrows indicating foreign key relationships.

### HTML Table

The `.html` file provides a browsable table view with color-coded primary keys (red) and foreign keys (blue).

## Design Philosophy

This follows the same principle as the `code-structure` static analysis tool:

> **Documents cannot drift from reality when they're generated from the actual code.**

- **Source of truth**: `schema.sql` defines the MySQL backend schema
- **Explicit relationships**: Foreign keys make relationships obvious (not implicit)
- **Build-time generation**: Diagrams regenerate on every build
- **Multiple formats**: GraphViz for rendering, Mermaid for GitHub, HTML for browsing

## Implementation

The generator consists of:

1. **SqlSchemaParser** - Parses CREATE TABLE statements using regex
2. **GraphVizGenerator** - Generates `.dot` format
3. **MermaidGenerator** - Generates `.mmd` ER diagrams
4. **HtmlTableGenerator** - Generates browsable HTML
5. **Gradle task** - Orchestrates parsing and generation

See `ai/schema-diagram-proposal.md` for the complete design rationale.

## Comparison to Manual Documentation

| Approach | Can Drift? | Format Options | Maintenance |
|----------|-----------|---------------|-------------|
| Manual ER diagram in docs/ | ✗ Yes | 1 format | Manual updates required |
| Generated from schema.sql | ✓ No | 3 formats | Automatic on build |

By generating from the actual schema file, the diagrams are always current and require zero maintenance effort.

## Future Enhancements

Possible improvements (not yet implemented):

- Detect cardinality (1:1, 1:N, M:N) from foreign keys
- Color-code entity types (users vs elections vs bridge tables)
- Cross-validate with DynamoDB patterns from code
- Validate naming conventions (all FKs end with _name?)
- Detect orphaned tables or missing indexes

See `ai/schema-diagram-proposal.md` for complete roadmap.
