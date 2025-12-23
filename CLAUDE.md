# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Workflows4s** is a Scala library for building long-running stateful processes (workflows). It merges Temporal's execution model, BPMN rendering, and native event-sourcing without runtime magic - just pure Scala ADTs.

- **Website**: https://business4s.github.io/workflows4s/
- **Discord**: https://bit.ly/business4s-discord
- **Language**: Scala 3.7.3
- **Build Tool**: sbt
- **Testing**: ScalaTest with cats-effect

## Development Commands

### Build and Test
```bash
# Run all tests
sbt test

# Run tests for a specific module
sbt workflows4s-core/test
sbt workflows4s-pekko/test
sbt workflows4s-doobie/test

# Run a single test class
sbt "workflows4s-core/testOnly workflows4s.wio.WIOHandleSignalTest"

# Compile all modules
sbt compile

# Compile tests
sbt Test/compile
```

### Code Quality
```bash
# Format code (REQUIRED before commits)
sbt scalafmtAll

# Check formatting without changes
sbt scalafmtCheckAll

# Run all pre-PR checks
sbt prePR
# Equivalent to: compile ; Test/compile ; test ; scalafmtCheckAll
```

### Running the Example Application
```bash
# Start the example server with auto-reload (debug port 5050)
sbt workflows4s-example/reStart

# Stop the server
sbt workflows4s-example/reStop

# Run specific main class
sbt "workflows4s-example/runMain workflows4s.example.api.ServerWithUI"
```

### Docker
```bash
# Build Docker image for example app
sbt workflows4s-example/Docker/publishLocal
```

### Frontend (Web UI)
```bash
# Install frontend dependencies (required before first build)
cd workflows4s-web-ui
npm install
cd ..

# The ScalaJS compilation happens automatically via sbt
```

### Website
```bash
cd website
yarn install
yarn build
yarn start  # Local development server
```

## Architecture Overview

### Core Abstractions

**WIO (Workflow IO)** - The central abstraction representing workflow operations:
- **Location**: `workflows4s-core/src/main/scala/workflows4s/wio/WIO.scala`
- Sealed trait with type parameters: `F[_]` (effect type), `In`, `Err`, `Out`, `Ctx`
- Key variants: `HandleSignal`, `RunIO`, `Pure`, `Timer`, `Loop`, `Fork`, `Parallel`, `ForEach`, `Embedded`, `HandleError`, `Checkpoint`, `Recovery`
- Compose operations with `>>>`, `handleErrorWith`, etc.
- Effect-polymorphic: works with `IO`, `Future`, `Id`, or custom effect types

**WorkflowContext** - Defines the workflow's type universe:
- **Location**: `workflows4s-core/src/main/scala/workflows4s/wio/WorkflowContext.scala`
- Contains `State` and `Event` types
- Users extend this trait to define their domain types
- For cats-effect IO workflows, extend `IOWorkflowContext` from `workflows4s-cats`

**Effect** - Typeclass abstracting over effect types:
- **Location**: `workflows4s-core/src/main/scala/workflows4s/runtime/instanceengine/Effect.scala`
- Provides `pure`, `map`, `flatMap`, `ref` (mutable reference) operations
- Implementations: `CatsEffect` (for cats-effect IO), `FutureEffect`, `IdEffect`

**ActiveWorkflow** - Running workflow instance:
- **Location**: `workflows4s-core/src/main/scala/workflows4s/wio/ActiveWorkflow.scala`
- Contains `id`, `wio`, `initialState`
- Methods: `queryState()`, `deliverSignal()`, `handleEvent()`, `wakeup()`

**SignalDef** - Type-safe external communication:
- **Location**: `workflows4s-core/src/main/scala/workflows4s/wio/SignalDef.scala`
- Defines request/response types for external interactions

### Event Sourcing Architecture

All state changes are events. State is reconstructed by replaying events through evaluators:
- **EventEvaluator**: Processes events to update workflow state
- **SignalEvaluator**: Handles incoming signals and produces events
- **ProceedEvaluator**: Advances workflow through pure computations
- **RunIOEvaluator**: Executes side effects (IO operations, timers)

**Key Principle**: Deterministic replay - all non-determinism (IO results, timestamps) is captured as events.

### Module Architecture

**workflows4s-core** - Core abstractions and in-memory runtime
- WIO DSL and interpreters (visitor pattern for evaluation)
- `InMemoryRuntime`: Non-persistent runtime for testing
- `WorkflowInstanceEngine`: Pluggable execution engine with decorator pattern
  - `BasicEngine`: Base implementation
  - `GreedyWorkflowInstanceEngine`: Auto-progresses workflows
  - `LoggingWorkflowInstanceEngine`: Adds logging
  - `WakingWorkflowInstanceEngine`: Time-based wakeups
  - `RegisteringWorkflowInstanceEngine`: Instance tracking
- `Effect` typeclass for effect abstraction

**workflows4s-cats** - Cats-effect integration
- `IOWorkflowContext`: Standard context for IO-based workflows
- `CatsEffect`: Effect instance for cats-effect IO
- `SleepingKnockerUpper`: Cats-effect based wakeup scheduler using fibers
- **Location**: `workflows4s-cats/src/main/scala/workflows4s/cats/`

**workflows4s-pekko** - Apache Pekko (Akka) integration
- Event-sourced actors via Pekko Persistence
- Cluster sharding for distributed workflows
- Each workflow instance = persistent actor
- External API uses `Future`, internal processing uses `IO`
- **Location**: `workflows4s-pekko/src/main/scala/workflows4s/runtime/pekko/`

**workflows4s-doobie** - Database-backed persistence
- PostgreSQL and SQLite support
- `DatabaseRuntime` stores events in relational DB
- State reconstructed by replaying events from database
- **Location**: `workflows4s-doobie/src/main/scala/workflows4s/doobie/`

**workflows4s-bpmn** - BPMN 2.0 visualization
- `BpmnRenderer` converts WIO to BPMN XML
- Visualize workflows in Camunda Modeler
- **Location**: `workflows4s-bpmn/src/main/scala/workflows4s/bpmn/`

**workflows4s-filesystem** - Filesystem-based scheduling
- `FilesystemKnockerUpper` for workflow wakeups
- **Location**: `workflows4s-filesystem/src/main/scala/workflows4s/runtime/wakeup/filesystem/`

**workflows4s-quartz** - Quartz scheduler integration
- `QuartzKnockerUpper` for production-grade scheduling
- **Location**: `workflows4s-quartz/src/main/scala/workflows4s/runtime/wakeup/quartz/`

**workflows4s-web-api-server** - HTTP API
- Tapir-based REST API for workflow management

**workflows4s-web-ui** - ScalaJS frontend
- Tyrian (Elm-like) web UI for workflow visualization

**workflows4s-example** - Example workflows
- Withdrawal workflow and CI checks engine examples
- Integration tests across all runtime backends

### KnockerUpper Pattern

Named after the historical profession, this component schedules workflow wakeups for timers, retries, and scheduled events.
- **Location**: `workflows4s-core/src/main/scala/workflows4s/runtime/wakeup/KnockerUpper.scala`
- Implementations: Quartz (production), Filesystem (lightweight), NoOp (testing)

### The "Greedy" Execution Model

After each state change, automatically calls `wakeup()` to execute pending IO operations and progress workflow as far as possible without external input. This provides Temporal-like behavior.
- **Location**: `workflows4s-core/src/main/scala/workflows4s/runtime/instanceengine/GreedyWorkflowInstanceEngine.scala`

### Builder Pattern for WIO Construction

Fluent API in `workflows4s-core/src/main/scala/workflows4s/wio/builders/`:
```scala
WIO
  .handleSignal(createPR)
  .using[State]
  .purely((in, req) => Event.Created(req))
  .handleEvent((in, evt) => State.Created(evt))
  .voidResponse
  .autoNamed
```

## Testing Patterns

### Test Structure
- Tests use ScalaTest `AnyFreeSpec` style
- Cats-effect `IO` for effects (`.unsafeRunSync()` in tests)
- ScalaMock for mocking

### Testing Multiple Runtimes
Tests often verify behavior across multiple backends:
```scala
class WithdrawalWorkflowTest extends AnyFreeSpec {
  "in-memory-sync" - { withdrawalTests(TestRuntimeAdapter.InMemorySync()) }
  "in-memory" - { withdrawalTests(TestRuntimeAdapter.InMemory()) }
}

class PostgresWithdrawalWorkflowTest extends AnyFreeSpec {
  "postgres" - { withdrawalTests(TestRuntimeAdapter.Postgres()) }
}
```

### Rendering Visualizations
Tests can generate BPMN and Mermaid diagrams:
```scala
TestUtils.renderBpmnToFile(workflow, "workflow.bpmn")
TestUtils.renderMermaidToFile(workflow, "workflow.mermaid")
```

### Key Test Utilities
- `TestRuntimeAdapter`: Id-based adapter for synchronous testing
- `IOTestRuntimeAdapter`: IO-based adapter for async/concurrent testing
- `workflows4s.testing` package: Testing utilities
- **Location**: `workflows4s-cats/src/test/scala/workflows4s/testing/`

## Code Style

### Formatting
- **scalafmt** version 3.10.1
- Scala 3 syntax (no significant indentation: `-no-indent`)
- Max line length: 150 characters
- Always run `sbt scalafmtAll` before committing

### Scala Compiler Options
- `-no-indent`: Disables significant indentation
- `-Xmax-inlines 64`: Increases inline limit
- `-explain-cyclic`: Explains cyclic errors
- `-Ydebug-cyclic`: Debug cyclic references

### Testing
- Test parallelization disabled for some modules (Pekko cluster tests would clash)
- `Test/parallelExecution := false` in relevant build.sbt modules

## Important Patterns

### Visitor Pattern
Different visitors traverse WIO structures for different purposes:
- Find available signals: `GetSignalDefsEvaluator`
- Advance workflow: `ProceedEvaluator`
- Process events: `EventEvaluator`
- Handle signals: `SignalEvaluator`
- Execute IO: `RunIOEvaluator`

### Decorator Pattern for Engines
Stack multiple `WorkflowInstanceEngine` implementations to add functionality:
```
BasicEngine → WithJavaTime → WithWakeups →
WithRegistering → WithGreedy → WithLogging
```

### Type-Level Programming
Extensive use of match types and dependent types for type-safe state transitions.

## Project Structure

```
workflows4s/
├── workflows4s-core/         # Core abstractions, effect typeclass
├── workflows4s-cats/         # Cats-effect integration (IOWorkflowContext)
├── workflows4s-bpmn/         # BPMN rendering
├── workflows4s-pekko/        # Pekko integration
├── workflows4s-doobie/       # Database persistence
├── workflows4s-filesystem/   # Filesystem scheduler
├── workflows4s-quartz/       # Quartz scheduler
├── workflows4s-web-api-shared/    # Shared API models
├── workflows4s-web-api-server/    # HTTP server
├── workflows4s-web-ui/            # ScalaJS frontend
├── workflows4s-web-ui-bundle/     # UI bundling
├── workflows4s-example/           # Example workflows
├── website/                       # Docusaurus website
└── project/                       # sbt build config
```

## Notes

- This is a pure Scala library with no bytecode manipulation (unlike Temporal)
- Workflows are ordinary Scala code using the WIO DSL
- State management is explicit via event sourcing
- Multiple runtime backends support different deployment scenarios
- BPMN visualization provides visual workflow representation
- The library prioritizes type safety and composability
