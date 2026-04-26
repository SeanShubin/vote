# Project Scripts

Project operations are exposed through a single Kotlin CLI in the `tools/` module.
Each platform has a thin shim that auto-rebuilds the CLI when sources change:

- `scripts/dev <subcommand> [args...]` (bash, macOS/Linux/Git-Bash)
- `scripts\dev.ps1 <subcommand> [args...]` (PowerShell on Windows)

Both shims delegate to the same Kotlin code, so behavior is identical across
platforms. Pass `--rebuild` to force a rebuild even if the launcher looks current.

## Common Tasks

### Build and test
The Gradle wrapper handles these directly — no shim needed:

| Task                | Command                                      |
| ------------------- | -------------------------------------------- |
| Build everything    | `./gradlew build` (or `gradlew.bat build`)   |
| Run tests           | `./gradlew test`                             |
| Clean & verify      | `./gradlew clean build test`                 |
| Clean only          | `./gradlew clean`                            |
| Run backend         | `./gradlew :backend:run --args="8080"`       |
| Run backend (MySQL) | `./gradlew :backend:run --args="8080 mysql"` |
| Regenerate wrapper  | `gradle wrapper --gradle-version=8.10`       |

### Developer subcommands

```
scripts/dev check-java                Verify the running JVM is Java 21
scripts/dev db-setup-mysql            Start MySQL container and apply schema
scripts/dev db-reset-mysql            Drop and recreate MySQL tables
scripts/dev db-teardown-mysql         Stop and remove the MySQL container
scripts/dev db-setup-dynamodb         Start DynamoDB Local and create tables
scripts/dev db-reset-dynamodb         Teardown and rebuild DynamoDB Local
scripts/dev db-teardown-dynamodb      Stop and remove DynamoDB Local
scripts/dev purge-mysql               Alias for db-reset-mysql
scripts/dev purge-dynamodb            Alias for db-reset-dynamodb
scripts/dev terminate-all             Kill backend (8080) and frontend (3000)
scripts/dev roll-logs                 Move current logs into logs/archive/
scripts/dev serve-frontend            Serve built frontend on http://localhost:3000
scripts/dev launch-fresh-mysql        Fresh MySQL launch (purge + start everything)
scripts/dev launch-fresh-dynamodb     Fresh DynamoDB launch (purge + start everything)
scripts/dev launch-keep-mysql         Launch MySQL keeping existing data
scripts/dev launch-keep-dynamodb      Launch DynamoDB keeping existing data
scripts/dev run-local                 Convenience: launch-fresh-dynamodb
scripts/dev launch-scenario-mysql     Launch with scenario data preloaded (MySQL)
scripts/dev launch-scenario-dynamodb  Launch with scenario data preloaded (DynamoDB)
scripts/dev convert-scenarios         Convert condorcet3 input.txt files to scenario JSON
scripts/dev setup-test-ballot         Create test users + election + ballots
scripts/dev test-lifecycle            End-to-end lifecycle test
scripts/dev inspect-mysql-all         Dump all MySQL tables
scripts/dev inspect-mysql-raw-query   Run an arbitrary SQL query
scripts/dev inspect-mysql-raw-schema  Show MySQL DDL, indexes, constraints
scripts/dev inspect-dynamodb-...      Various DynamoDB inspection commands
```

Run `scripts/dev --help` for the authoritative subcommand list.

## Requirements

- **JDK 21** — the Gradle build requires it. Kotlin 2.0.21 rejects JDK 22+ as the
  daemon runtime.
- **Docker** — required for `db-setup-*` and the launch scripts.

### Version management

`.tool-versions` (asdf format) declares the expected Java version. Recommended
installer: **[mise](https://mise.jdx.dev)** — a single binary that works on
macOS, Linux, and Windows and reads the same `.tool-versions` file as asdf. On
macOS, asdf itself also works.

Once mise is installed and you've run `mise install` in the project directory,
your shell automatically gets `JAVA_HOME` set whenever you `cd` into the
project — including for `scripts/dev` and direct `./gradlew` invocations.

Even without mise/asdf, the shims (`scripts/dev`, `scripts/dev.ps1`) parse
`.tool-versions` and search known install locations (mise/asdf install dirs,
`Program Files\Amazon Corretto`, `~/.jdks`, `/usr/lib/jvm`, macOS JVM bundles)
for a JDK matching the major version. They override `JAVA_HOME` for the
duration of the call when the existing one points at the wrong major. If
nothing is found, the shim warns and proceeds with whatever JAVA_HOME is set.

## Notes

- The Kotlin CLI binary lives in `tools/build/install/vote-dev/`. The shim
  rebuilds it via `./gradlew :tools:installDist` only when sources have
  changed (compared to the launcher's mtime). The fast path is well under a
  second on both shells.
- Every developer-facing operation is implemented in `tools/src/main/kotlin/`.
  Add new operations there as Clikt subcommands — no new shim files needed.
