# Effect System Abstraction Plan for workflows4s

## Goal
Abstract workflows4s from Cats Effect to support multiple effect systems (IO, ZIO, Future, Id) using a Result ADT pattern inspired by Besom. Lock in effect type at workflow creation, hide it from users afterward. No casts.

**Key Constraint**: `workflows4s-core` should have NO Cats Effect dependency - only normal `cats` (for `Id`). Cats Effect support moves to `workflows4s-cats` module.

## Current Progress (fix-internal-signatures branch)

### Completed:
- [x] `WorkflowResult` ADT created at `workflows4s-core/.../wio/WorkflowResult.scala`
- [x] `WorkflowRef` trait created at `workflows4s-core/.../wio/WorkflowRef.scala`
- [x] `Effect.interpret()` method added
- [x] `Effect.futureEffect` added
- [x] `WorkflowContext` updated with `type Eff[_]` and `implicit def effect`

### In Progress:
- [ ] Builders have inconsistent F[_] parameters (branch added F to some but not all)
- [ ] Need to make AllBuilders work with Ctx's Eff type

### Key Issue:
The branch `fix-internal-signatures` has partial changes adding `F[_]` to ~50 files but is inconsistent. Two approaches:
1. **Fix F[_] everywhere**: Make all builders take `F[_]` consistently
2. **Use path-dependent Eff**: Derive F from `Ctx` using `ctx.Eff`

## Current State
- `Effect[F[_]]` trait exists at `workflows4s-core/.../instanceengine/Effect.scala` with IO and Id instances
- `WIO[F[_], In, Err, Out, Ctx]` is effect-polymorphic via F parameter
- `RunIO.buildIO: In => F[Evt]` couples to concrete effect types
- `SignalHandler` similarly uses `F[_]`
- Runtimes use Cats Effect primitives (Ref, AtomicCell, Semaphore)

## Design

### 1. Result ADT (Free Monad-like)
Create `WorkflowResult[+A]` that captures operations for deferred interpretation:

```scala
// workflows4s-core/src/main/scala/workflows4s/wio/WorkflowResult.scala
enum WorkflowResult[+A]:
  case Pure(value: A)
  case Fail(error: Throwable)
  case Defer(thunk: () => A)
  case FlatMap[B, A](base: WorkflowResult[B], f: B => WorkflowResult[A])
  case HandleError[A](base: WorkflowResult[A], handler: Throwable => WorkflowResult[A])
  case RealTime extends WorkflowResult[Instant]
  case GetRef[T](ref: WorkflowRef[T]) extends WorkflowResult[T]
  case UpdateRef[T](ref: WorkflowRef[T], f: T => T) extends WorkflowResult[Unit]
```

### 2. WorkflowRef Abstraction
Abstract reference type (Ref replacement):

```scala
// workflows4s-core/src/main/scala/workflows4s/wio/WorkflowRef.scala
trait WorkflowRef[A]:
  def id: WorkflowRef.Id

// Operations via Result ADT
WorkflowResult.GetRef(ref)
WorkflowResult.UpdateRef(ref, f)
```

### 3. Extend Effect Trait
Add Ref operations and Result interpretation to existing Effect trait:

```scala
// workflows4s-core/.../instanceengine/Effect.scala (modify)
trait Effect[F[_]]:
  // ... existing operations ...

  // Ref operations (NEW)
  def createRef[A](initial: A): F[WorkflowRefImpl[F, A]]
  def getRef[A](ref: WorkflowRefImpl[F, A]): F[A]
  def updateRef[A](ref: WorkflowRefImpl[F, A])(f: A => A): F[Unit]

  // Result interpretation (NEW)
  def interpret[A](result: WorkflowResult[A]): F[A]
```

### 4. WorkflowContext with Effect Type
Lock effect type at context level via path-dependent type:

```scala
// workflows4s-core/.../wio/WorkflowContext.scala (modify)
trait WorkflowContext { ctx =>
  type Event
  type State
  type Eff[_]  // NEW: effect type locked here

  protected def effect: Effect[Eff]

  type Ctx = WorkflowContext.AUX[State, Event, Eff]
  type WIO[-In, +Err, +Out <: State] = workflows4s.wio.WIO[Eff, In, Err, Out, Ctx]
  // ...
}
```

### 5. Update WIO to use WorkflowResult
Change `RunIO`, `HandleSignal`, `Checkpoint`, `Retry` to store `WorkflowResult`:

```scala
// workflows4s-core/.../wio/WIO.scala (modify)
case class RunIO[F[_], Ctx, -In, +Err, +Out, Evt](
  buildResult: In => WorkflowResult[Evt],  // Changed from F[Evt]
  evtHandler: EventHandler[...],
  meta: RunIO.Meta,
) extends WIO[...]
```

### 6. Update SignalHandler

```scala
// workflows4s-core/.../internal/SignalHandler.scala (modify)
case class SignalHandler[Sig, Evt, In](
  handle: (In, Sig) => WorkflowResult[Evt]  // Changed from F
)
```

### 7. Update Builders
Change builder APIs to accept `WorkflowResult`:

```scala
// workflows4s-core/.../builders/RunIOBuilder.scala (modify)
def apply[Evt](f: Input => WorkflowResult[Evt]): Step2[Input, Evt]
def pure[Evt](f: Input => Evt): Step2[Input, Evt]  // convenience
```

### 8. Module Pattern for Effect Systems

```scala
// workflows4s-core/.../WorkflowModule.scala (NEW)
trait WorkflowModule:
  type Eff[_]
  given effect: Effect[Eff]

// In core (no Cats Effect dependency - only cats for Id)
object FutureWorkflows extends WorkflowModule:
  type Eff[A] = Future[A]
  given effect: Effect[Future] = Effect.futureEffect

object SyncWorkflows extends WorkflowModule:
  type Eff[A] = Id[A]  // cats.Id from normal cats library
  given effect: Effect[Id] = Effect.idEffect

// workflows4s-cats/... (NEW module - Cats Effect dependency here)
object CatsWorkflows extends WorkflowModule:
  type Eff[A] = IO[A]
  given effect: Effect[IO] = Effect.ioEffect
  // Also provides: CatsRef[A] implementing WorkflowRef

// workflows4s-zio/... (NEW module)
object ZIOWorkflows extends WorkflowModule:
  type Eff[A] = Task[A]
  given effect: Effect[Task] = zioEffect
  // Also provides: ZIORef[A] implementing WorkflowRef
```

## Implementation Steps

### Phase 1: Core Abstractions (non-breaking)
1. Create `WorkflowResult` ADT
2. Create `WorkflowRef` trait (abstract reference)
3. Add Ref operations to `Effect` trait
4. Add `interpret` method to `Effect` trait
5. Add Effect instances in core: `Future`, `Id` (no Cats Effect deps)
6. Move `Effect[IO]` instance to `workflows4s-cats` module

### Phase 2: WorkflowContext Changes (breaking)
1. Add `Eff[_]` type member to `WorkflowContext`
2. Update `WorkflowContext.AUX` to include effect type
3. Update type aliases in `WorkflowContext.WIO`

### Phase 3: WIO Changes (breaking)
1. Update `RunIO` to use `WorkflowResult` instead of `F[Evt]`
2. Update `HandleSignal` similarly
3. Update `Checkpoint.genEvent` to use `WorkflowResult`
4. Update `Retry.onError` to use `WorkflowResult`
5. Update `SignalHandler` to use `WorkflowResult`

### Phase 4: Builder Changes (breaking)
1. Update `RunIOBuilder` to accept `WorkflowResult`
2. Update `HandleSignalBuilder` to accept `WorkflowResult`
3. Add convenience methods for pure operations

### Phase 5: Evaluator Changes
1. Update `RunIOEvaluator` to interpret `WorkflowResult`
2. Update `SignalEvaluator` similarly
3. Update other evaluators as needed

### Phase 6: Runtime Changes
1. Remove `Semaphore` usage from `InMemoryWorkflowInstance` (simplify for now)
2. Remove `AtomicCell` usage - replace with abstract `WorkflowRef`
3. Abstract `Ref` via `WorkflowRef` trait with effect-specific implementations
4. Move Cats Effect IO runtime to `workflows4s-cats` module

### Phase 7: Module Structure
1. Create `workflows4s-cats` module (move IO-specific code)
2. Create `workflows4s-zio` module
3. Keep `Future` and `Id` support in core (no external dependencies)

## Critical Files to Modify

**Core abstractions:**
- `workflows4s-core/src/main/scala/workflows4s/wio/WorkflowResult.scala` (NEW)
- `workflows4s-core/src/main/scala/workflows4s/wio/WorkflowRef.scala` (NEW)
- `workflows4s-core/src/main/scala/workflows4s/runtime/instanceengine/Effect.scala`

**Context & WIO:**
- `workflows4s-core/src/main/scala/workflows4s/wio/WorkflowContext.scala`
- `workflows4s-core/src/main/scala/workflows4s/wio/WIO.scala`

**Internal:**
- `workflows4s-core/src/main/scala/workflows4s/wio/internal/SignalHandler.scala`
- `workflows4s-core/src/main/scala/workflows4s/wio/internal/RunIOEvaluator.scala`
- `workflows4s-core/src/main/scala/workflows4s/wio/internal/SignalEvaluator.scala`

**Builders:**
- `workflows4s-core/src/main/scala/workflows4s/wio/builders/RunIOBuilder.scala`
- `workflows4s-core/src/main/scala/workflows4s/wio/builders/HandleSignalBuilder.scala`

**Runtime:**
- `workflows4s-core/src/main/scala/workflows4s/runtime/InMemoryRuntime.scala`
- `workflows4s-core/src/main/scala/workflows4s/runtime/InMemoryWorkflowInstance.scala`
- `workflows4s-core/src/main/scala/workflows4s/runtime/InMemorySyncRuntime.scala`

## User-Facing API After Migration

```scala
// Define workflow context with locked effect type
import workflows4s.cats.*  // or workflows4s.zio.*, workflows4s.future.*

object MyWorkflowContext extends WorkflowContext {
  type State = MyState
  type Event = MyEvent
  type Eff[A] = IO[A]  // Locked here
  protected val effect = summon[Effect[Eff]]
}

import MyWorkflowContext.WIO

// Use WorkflowResult in workflow definitions
val workflow: WIO[Unit, Nothing, MyState] =
  WIO.runIO[Unit] { _ =>
    for {
      time <- WorkflowResult.realTime
      _ <- WorkflowResult.defer(println(s"Starting at $time"))
    } yield MyEvent.Started(time)
  }
  .handleEvent((_, evt) => MyState.Running)
  .done
```
