# Effect Type Propagation through WorkflowContext - Scala 3 Limitations

## Goal

Propagate the effect type `F[_]` through `WorkflowContext` so developers commit to a single effect type at workflow definition time (e.g., `IO`, `ZIO`, etc.).

## Approaches Considered

### 1. Type Projections (`Ctx#F`)

```scala
trait WorkflowContext {
  type F[_]
}

// Usage attempt:
def runIO[Evt](wio: WIO.RunIO[Ctx#F, In, Err, Out, Evt]): Result
```

**Problem**: Type projections on abstract types are deprecated in Scala 3 and don't work reliably. The compiler cannot properly resolve `Ctx#F[A]` when `Ctx` is abstract.

### 2. Match Types with Higher-Kinded Types

```scala
trait WorkflowContext {
  type F[_]
}

type AuxF[F0[_]] = WorkflowContext { type F[A] = F0[A] }

type WCF[T <: WorkflowContext] = T match {
  case AuxF[f] => f
}
```

**Problem**: Scala 3 match types don't support extracting higher-kinded type parameters. The compiler reports:
```
The pattern contains an unaccounted type parameter `f`
```

This is a fundamental limitation - match types can extract simple types but not HKTs.

### 3. Path-Dependent Types (requires value)

```scala
def example(ctx: WorkflowContext): ctx.F[Int] = ???
```

**Problem**: Works when you have a value `ctx`, but in type signatures we typically only have the type `Ctx`, not a concrete value. WIO nodes need to be defined at the type level, not dependent on runtime values.

### 4. Explicit F Type Parameter (Chosen Approach)

```scala
case class RunIO[Ctx <: WorkflowContext, F[_], -In, +Err, +Out <: WCState[Ctx], Evt](
  buildIO: In => F[Evt],
  // ...
) extends WIO[In, Err, Out, Ctx]
```

**Why this works**: The `F[_]` type parameter is explicitly threaded through all effect-using WIO nodes and builders. While more verbose, it's the most straightforward approach that works with Scala 3's type system.

## Implementation Details

### WorkflowContext

```scala
trait WorkflowContext { self =>
  type Event
  type State
  type F[_]
  given effect: Effect[F]

  type Ctx = WorkflowContext.AUX[State, Event, F]

  object WIO extends AllBuilders[Ctx, F](using effect) {
    // ...
  }
}
```

### Effect-using WIO nodes

Nodes that use effects have `F[_]` as a type parameter:
- `RunIO[Ctx, F, In, Err, Out, Evt]`
- `HandleSignal[Ctx, F, In, Out, Err, Sig, Resp, Evt]`
- `Checkpoint[Ctx, F, In, Err, Out, Evt]`
- `Retry[Ctx, F, In, Err, Out]`
- `Interruption[Ctx, F, Err, Out]`

### Builders

Builders are parameterized by both `Ctx` and `F`:

```scala
trait AllBuilders[Ctx <: WorkflowContext, F[_]](using val ctxEffect: Effect[F])
  extends HandleSignalBuilder.Step0[Ctx, F]
  with RunIOBuilder.Step0[Ctx, F]
  // ...
```

## Trade-offs

**Pros**:
- Works reliably with Scala 3's type system
- Type-safe: F is known at compile time
- Developers commit to one effect type at definition time

**Cons**:
- More verbose type signatures on WIO nodes
- F must be explicitly passed through builders
- Visitor pattern methods need F as a type parameter

## Reproducible Example

Here's a minimal Scala 3 script demonstrating the HKT match type limitation:

```scala
//> using scala 3.3

// Approach 1: Type projection - deprecated in Scala 3
trait Context1 {
  type F[_]
}

// This won't work well in Scala 3:
// type ExtractF[C <: Context1] = C#F  // Deprecated syntax, doesn't work with abstract types

// Approach 2: Match type with HKT - doesn't work
trait AuxF[F0[_]] extends Context1 {
  type F[A] = F0[A]
}

// This produces: "The pattern contains an unaccounted type parameter"
// type ExtractF2[C <: Context1] = C match {
//   case AuxF[f] => f
// }

// Approach 3: Path-dependent types - requires a value
def useWithValue[C <: Context1](ctx: C): ctx.F[Int] = ???
// This works but we can't use it in type signatures without a concrete value

// What works: Explicit F as type parameter
trait WorkWithExplicitF[C <: Context1, F[_]] {
  def operation: F[Int]
}

// Example usage showing the limitation
object Main extends App {
  // The following would be the ideal syntax but doesn't work:
  // trait IdealAPI[C <: Context1] {
  //   def runEffect: ExtractF[C][Int]  // Extract F from C
  // }

  // Instead, we need explicit F:
  trait WorkingAPI[C <: Context1, F[_]] {
    def runEffect: F[Int]
  }

  println("Scala 3 HKT match type limitation demonstrated")
}
```

To run this example:
```bash
# Save as limitation.scala and run with scala-cli:
scala-cli run limitation.scala
```

The key limitation is at the match type definition - Scala 3 cannot extract a higher-kinded type parameter `f` from a pattern like `AuxF[f]`.

## Future Considerations

If Scala 3 adds better support for:
1. Type projections on abstract higher-kinded types
2. Match types that can extract HKT parameters

...then a cleaner solution might become possible where F is derived directly from Ctx.
