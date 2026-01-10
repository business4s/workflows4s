# Effect System Requirements

This document describes the requirements for implementing a custom `Effect[F[_]]` instance for workflows4s. Follow these requirements to ensure your effect type integrates correctly with the workflow runtime.

## Overview

The `Effect[F[_]]` typeclass abstracts over effect types (IO, Future, Id, etc.) to allow workflows to be effect-polymorphic. Each implementation must provide the operations defined in the trait.

## MoSCoW Requirements

### Must Have (Critical - workflows will break without these)

These operations are essential for basic workflow execution:

| Operation | Signature | Description |
|-----------|-----------|-------------|
| `pure` | `A => F[A]` | Lift a value into the effect |
| `flatMap` | `F[A] => (A => F[B]) => F[B]` | Sequential composition |
| `map` | `F[A] => (A => B) => F[B]` | Transform the result |
| `delay` | `(=> A) => F[A]` | Suspend a side-effectful computation |
| `raiseError` | `Throwable => F[A]` | Create a failed effect |
| `handleErrorWith` | `F[A] => (Throwable => F[A]) => F[A]` | Recover from errors. **Note:** First parameter is by-name to support synchronous effects |
| `ref` | `A => F[Ref[F, A]]` | Create a mutable reference for state management |
| `runSyncUnsafe` | `F[A] => A` | Execute effect synchronously (for tests and integration) |

### Should Have (Important - some features may not work)

These operations enable advanced workflow features:

| Operation | Signature | Description |
|-----------|-----------|-------------|
| `sleep` | `FiniteDuration => F[Unit]` | Time-based delays (required for timers/retries) |
| `createMutex` | `F[Mutex]` | Create a mutex for synchronization |
| `withLock` | `Mutex => (=> F[A]) => F[A]` | Execute while holding lock. **Note:** Effect parameter is by-name for eager effects like Future |
| `start` | `F[A] => F[Fiber[F, A]]` | Start background computation (required for SleepingKnockerUpper) |
| `guaranteeCase` | `F[A] => (Outcome[A] => F[Unit]) => F[A]` | Resource cleanup with outcome information |

### Could Have (Nice to have - convenience operations)

These are derived operations with default implementations:

| Operation | Signature | Description |
|-----------|-----------|-------------|
| `void` | `F[A] => F[Unit]` | Discard result |
| `unit` | `F[Unit]` | Pure unit value |
| `as` | `F[A] => B => F[B]` | Replace result with constant |
| `whenA` | `Boolean => (=> F[Unit]) => F[Unit]` | Conditional execution |
| `traverse` | `List[A] => (A => F[B]) => F[List[B]]` | Map and sequence |
| `sequence` | `List[F[A]] => F[List[A]]` | Sequence effects |
| `attempt` | `F[A] => F[Either[Throwable, A]]` | Capture errors as values |
| `fromOption` | `Option[A] => Throwable => F[A]` | Convert Option to effect |
| `fromEither` | `Either[Throwable, A] => F[A]` | Convert Either to effect |

### Won't Have (Out of scope)

The Effect typeclass intentionally excludes:

- **Parallel execution**: Use `traverse` for sequential; parallel requires additional constraints
- **Resource management**: Use `guaranteeCase` for cleanup; full bracket semantics not included
- **Cancellation propagation**: `Fiber.cancel` cancels, but no structured concurrency
- **Real-time clock access**: Time is handled externally via `java.time.Clock`
- **Async boundaries**: No `async` or callback-based construction

## Supporting Types

### Ref[F, A]

Mutable reference with atomic operations:

```scala
trait Ref[F[_], A] {
  def get: F[A]
  def set(a: A): F[Unit]
  def update(f: A => A): F[Unit]
  def modify[B](f: A => (A, B)): F[B]
  def getAndUpdate(f: A => A): F[A]
}
```

### Fiber[F, A]

Background computation handle:

```scala
trait Fiber[F[_], A] {
  def cancel: F[Unit]
  def join: F[Outcome[A]]
}
```

### Outcome[A]

Result of a fiber execution:

```scala
enum Outcome[+A] {
  case Succeeded(value: A)
  case Errored(error: Throwable)
  case Canceled
}
```

## Test Suites

When implementing a new Effect instance, use the provided test suites to verify correctness:

### EffectTestSuite

Location: `workflows4s-core/src/test/scala/workflows4s/runtime/instanceengine/EffectTestSuite.scala`

Contains 20+ tests covering all Effect operations:

```scala
class MyEffectTest extends EffectTestSuite[MyEffect] {
  given effect: Effect[MyEffect] = MyEffect.instance

  "MyEffect" - {
    effectTests()  // Runs all standard Effect tests
  }
}
```

Tests included:
- `pure`, `map`, `flatMap`, `delay`
- `raiseError` and `handleErrorWith`
- `Ref` operations: get, set, update, modify, getAndUpdate, atomic updates
- `withLock`: execution, release on success/failure, mutual exclusion
- `start` and `join`
- `guaranteeCase`: finalizer on success/failure
- `traverse`, `sequence`, `attempt`, `sleep`

### SleepingKnockerUpperTestSuite

Location: `workflows4s-core/src/test/scala/workflows4s/runtime/wakeup/SleepingKnockerUpperTestSuite.scala`

Tests for the wakeup scheduler with your effect type:

```scala
class MySleepingKnockerUpperTest extends SleepingKnockerUpperTestSuite[MyEffect] {
  given effect: Effect[MyEffect] = MyEffect.instance

  override def sleep(duration: FiniteDuration): MyEffect[Unit] = MyEffect.sleep(duration)
  override def randomWfId(): WorkflowInstanceId = TestUtils.randomWfId()

  "SleepingKnockerUpper (MyEffect)" - {
    sleepingKnockerUpperTests()
  }
}
```

### WithdrawalWorkflowTestSuite

Location: `workflows4s-tck/src/test/scala/workflows4s/example/withdrawal/WithdrawalWorkflowTestSuite.scala`

Integration tests for complete workflow execution:

```scala
class MyWithdrawalWorkflowTest extends WithdrawalWorkflowTestSuite[MyEffect] {
  override given effect: Effect[MyEffect] = MyEffect.instance
  // ... adapter setup
}
```

## Existing Implementations

Reference implementations:

| Effect Type | Location | Notes |
|-------------|----------|-------|
| `cats.Id` | `Effect.idEffect` in core | Synchronous, throws exceptions |
| `cats.effect.IO` | `CatsEffect.ioEffect` | Async, cats-effect based |
| `LazyFuture` | `FutureEffect.futureEffect` | Deferred Future for Pekko integration |

## Implementation Checklist

- [ ] Implement all "Must Have" operations
- [ ] Implement "Should Have" operations for timer/retry support
- [ ] Pass `EffectTestSuite` tests
- [ ] Pass `SleepingKnockerUpperTestSuite` tests (if using timers)
- [ ] Pass `WithdrawalWorkflowTestSuite` tests (full integration)
- [ ] Handle by-name parameters correctly for eager effect types
- [ ] Ensure `Ref` operations are thread-safe/atomic
- [ ] Document any limitations or deviations

## Notes for Eager Effect Types (e.g., Future)

For effect types that evaluate eagerly (like `Future`), pay special attention to:

1. **By-name parameters**: `handleErrorWith(fa: => F[A])` and `withLock(m)(fa: => F[A])` use by-name to prevent premature evaluation
2. **Deferred construction**: Consider wrapping in a lazy wrapper (see `LazyFuture` in `FutureEffect.scala`)
3. **Referential transparency**: Ensure effects are not executed until explicitly run
