# Add Effect Type Parameter to WIO

## Summary

This PR introduces effect polymorphism to the WIO type, allowing workflows to be parameterized over any effect type `F[_]` (such as `IO`, `Id`, or `Future`). This provides greater flexibility while maintaining backward compatibility for the most common use case (cats-effect IO).

## What Changed

### Core Type Change

The `WIO` type now includes an effect type parameter:

```scala
// Before
sealed trait WIO[-In, +Err, +Out <: WCState[Ctx], Ctx <: WorkflowContext]

// After
sealed trait WIO[F[_], -In, +Err, +Out <: WCState[Ctx], Ctx <: WorkflowContext]
```

### DSL Usage - No Changes Required

**The workflow DSL remains exactly the same.** When building workflows, you don't need to think about the effect type at all:

```scala
object WithdrawalWorkflow {

  object Context extends IOWorkflowContext {
    override type Event = WithdrawalEvent
    override type State = WithdrawalData
  }

  import Context.WIO

  // DSL usage is unchanged - looks exactly the same as before
  val workflow: WIO[WithdrawalData.Empty, Nothing, WithdrawalData.Completed] =
    for {
      _ <- validate
      _ <- calculateFees
      _ <- putFundsOnHold
      _ <- runChecks
      _ <- execute
      s <- releaseFunds
    } yield s

  private def validate: WIO[Any, InvalidInput, WithdrawalData.Initiated] =
    WIO
      .handleSignal(Signals.createWithdrawal)
      .using[Any]
      .purely { (_, signal) => /* ... */ }
      .handleEventWithError { (_, event) => /* ... */ }
      .voidResponse
      .autoNamed

  private def calculateFees: WIO[WithdrawalData.Initiated, Nothing, WithdrawalData.Validated] =
    WIO
      .runIO[WithdrawalData.Initiated](state => service.calculateFees(state.amount).map(/*...*/))
      .handleEvent { (state, event) => state.validated(event.fee) }
      .autoNamed()
}
```

### Infrastructure Setup - Slightly Different

When setting up the runtime infrastructure, you now work with the `Effect[F]` type class:

```scala
import cats.effect.IO
import workflows4s.runtime.InMemoryRuntime
import workflows4s.runtime.instanceengine.{Effect, WorkflowInstanceEngine}

// Effect[IO] instance is provided automatically via given
val engine: WorkflowInstanceEngine[IO] = WorkflowInstanceEngine.basic[IO]()

val runtime: InMemoryRuntime[IO, MyContext.Ctx] = InMemoryRuntime.create[IO, MyContext.Ctx](
  workflow = myWorkflow,
  initialState = MyState.Initial,
  engine = engine,
)

// Create and use workflow instances
val instance: IO[WorkflowInstance[IO, MyState]] = runtime.createInstance("workflow-123")
```

### Effect Type Class

A new `Effect[F[_]]` type class provides the minimal interface needed for workflow execution:

```scala
trait Effect[F[_]] {
  def pure[A](a: A): F[A]
  def flatMap[A, B](fa: F[A])(f: A => F[B]): F[B]
  def map[A, B](fa: F[A])(f: A => B): F[B]
  def raiseError[A](e: Throwable): F[A]
  def handleErrorWith[A](fa: => F[A])(f: Throwable => F[A]): F[A]
  def delay[A](a: => A): F[A]
  def sleep(duration: FiniteDuration): F[Unit]
  def realTimeInstant: F[Instant]
  // ... derived operations
}
```

Built-in instances are provided for:
- `cats.effect.IO` (recommended for production)
- `cats.Id` (useful for testing synchronous workflows)
- `scala.concurrent.Future` (when working with Future-based code)

## Migration Guide

1. **Workflow definitions**: No changes needed
2. **Runtime setup**: Update to use the new `Effect`-parameterized APIs
3. **Custom runtimes**: Implement or use an `Effect[F]` instance for your effect type

## Benefits

- **Flexibility**: Use any effect type that fits your application
- **Testing**: Use `Id` for simple synchronous tests without IO overhead
- **Interop**: Integrate with Future-based or other effect systems
- **Type safety**: Effect type is tracked at compile time
