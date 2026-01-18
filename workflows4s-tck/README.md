# workflows4s-tck

Technology Compatibility Kit (TCK) for testing workflows4s runtime implementations.

## Purpose

This module provides the **"golden sample" workflow** and **reusable test suites** to verify that runtime implementations (Pekko, Postgres, SQLite, in-memory) behave correctly. All runtimes are tested against the same workflow definitions to ensure consistency.

## Contents

### Main Sources (`src/main/scala`)

- **WithdrawalWorkflow** - The golden sample workflow that all runtimes are tested against. Demonstrates signals, timers, error handling, retries, and embedded workflows.
- **ChecksEngine** - A generic embedded workflow used to test the WIO embedding feature. Demonstrates retry logic, timeouts, and operator review flows.
- **Domain models** - WithdrawalData, WithdrawalEvent, WithdrawalSignal, ChecksState, etc.

### Test Sources (`src/test/scala`)

- **WithdrawalWorkflowTestSuite[F]** - Reusable test suite that verifies workflow behavior across runtimes
- **ChecksEngineTestSuite[F]** - Reusable test suite for the embedded checks workflow
- **Test utilities** - TestWithdrawalService, StaticCheck, test contexts

## Usage

To test a new runtime implementation:

```scala
class MyRuntimeWithdrawalTest extends AnyFreeSpec with WithdrawalWorkflowTestSuite[IO] {
  override given effect: Effect[IO] = CatsEffect.ioEffect

  "my-runtime" - {
    val adapter = new MyRuntimeAdapter[testContext.Context.Ctx](...)
    withdrawalTests(adapter)
  }
}
```

## Design Principles

1. **Golden sample testing** - All runtimes tested against the same WithdrawalWorkflow
2. **Effect polymorphism** - Works with IO, Future, or any effect type via `F[_]`
3. **No runtime dependencies** - TCK only depends on `workflows4s-core`
