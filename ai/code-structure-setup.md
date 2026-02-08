# Code Structure Gradle Plugin Setup

## Current Status

The project now uses the [code-structure](https://github.com/SeanShubin/code-structure) Gradle plugin for automated dependency analysis.

## Configuration

### Files Modified

1. **`settings.gradle.kts`** - Added `pluginManagement` block with `mavenLocal()` repository
2. **`build.gradle.kts`** - Added plugin declaration: `id("com.seanshubin.code.structure") version "1.1.1"`
3. **`code-structure-config.json`** - Configuration file for analysis rules

### Using Local Maven Build (Current)

Currently using version `1.1.1` installed to local Maven repository (`~/.m2/repository`).

The `pluginManagement` block in `settings.gradle.kts` includes `mavenLocal()` first:

```kotlin
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        mavenCentral()
    }
}
```

This ensures Gradle finds the locally-installed plugin before checking remote repositories.

### Transition to Published Version

Once the plugin is published to Gradle Plugin Portal, **no code changes are needed**. The existing configuration will work because:

1. Gradle checks repositories in order: `mavenLocal()` → `gradlePluginPortal()` → `mavenCentral()`
2. If version `1.1.1` exists on Gradle Plugin Portal, users without the local build will fetch it from there
3. Users with the local build will continue using their local version (identical code)

The `pluginManagement` block can remain as-is, or optionally be simplified to:

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
```

## Running the Analysis

```bash
./gradlew analyzeCodeStructure
```

### Output

Results are written to `generated/code-structure/`:
- `browse/` - Browsable HTML reports
- `count/` - Metrics files showing cycle counts, vertical dependencies
- `diff/` - Comparison reports (if running multiple times)
- `observations.json` - Raw dependency data

### Status: ✅ Production Ready

The plugin now works correctly:
- ✅ Analysis completes successfully
- ✅ All files are generated
- ✅ Metrics are accurate
- ✅ Gradle daemon stays alive (no crashes)
- ✅ Proper error reporting via GradleException when errors detected

**Fixed in version 1.1.1**: Refactored `EntryPoint` to separate CLI usage (`main()` with `exitProcess()`) from library usage (`execute()` that returns exit code).

## Configuration Details

### Source Files

The plugin scans:
- **Kotlin source files**: `.*/src/.*/kotlin/.*\.kt`
- **Compiled classes**: `.*/build/classes/.*\.class`

### Error Thresholds

Currently configured to fail the build if any of these are detected:
- Direct cycles between classes
- Group cycles between packages
- Ancestor-depends-on-descendant violations
- Descendant-depends-on-ancestor violations

Maximum allowed error count: `0`

### Adjusting Configuration

Edit `code-structure-config.json` to:
- Change which files are analyzed
- Adjust error thresholds
- Enable/disable specific checks
- Configure output format

See [code-structure documentation](https://github.com/SeanShubin/code-structure) for full configuration reference.

## Comparison to Schema Diagram Generation

Both tools follow the same philosophy:

| Aspect | schema-diagram | code-structure |
|--------|---------------|----------------|
| **Input** | `schema.sql` | Compiled `.class` files |
| **Analyzes** | Table relationships | Code dependencies |
| **Detects** | Foreign key relationships | Cycles, vertical deps |
| **Output** | ER diagrams (GraphViz, Mermaid, HTML) | Dependency graphs, metrics |
| **Cannot Drift** | ✅ Generated from actual schema | ✅ Analyzes actual bytecode |

Both ensure documentation cannot become stale because they analyze the actual source of truth (schema file or compiled code) rather than relying on manually-maintained documentation.
