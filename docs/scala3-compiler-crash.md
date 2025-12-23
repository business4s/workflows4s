# Scala 3 Compiler Crash: Nested Traits with Type Parameters

## Issue Summary

When adding the `F[_]` effect type parameter to the workflow builders, the Scala 3 compiler (version 3.7.3) crashes with an `AssertionError` when compiling test files.

## Error Message

```
java.lang.AssertionError: assertion failed: trait AllBuilders has non-class parent:
AppliedType(TypeRef(TermRef(TermRef(ThisType(TypeRef(NoPrefix,module class wio)),object builders),RunIOBuilder),Step0),
List(TypeRef(ThisType(TypeRef(TermRef(ThisType(TypeRef(NoPrefix,module class wio)),object builders),trait AllBuilders)),type F),
TypeRef(ThisType(TypeRef(TermRef(ThisType(TypeRef(NoPrefix,module class wio)),object builders),trait AllBuilders)),type Ctx)))
```

## Problematic Code Pattern

The issue occurs when a trait extends another trait that is:
1. Nested inside a companion object
2. Has higher-kinded type parameters (`F[_]`)

### Before (causes crash):

```scala
// RunIOBuilder.scala
object RunIOBuilder {
  trait Step0[F[_], Ctx <: WorkflowContext] {
    def runIO[Input] = new RunIOBuilderStep1[Input]
    // ...
  }
}

// AllBuilders.scala
trait AllBuilders[F[_], Ctx <: WorkflowContext]
    extends WIOBuilderMethods[F, Ctx]
    with HandleSignalBuilder.Step0[F, Ctx]
    with LoopBuilder.Step0[F, Ctx]
    with AwaitBuilder.Step0[F, Ctx]
    with ForkBuilder.Step0[F, Ctx]
    with BranchBuilder.Step0[F, Ctx]
    with DraftBuilder.Step0[F, Ctx]
    with RunIOBuilder.Step0[F, Ctx]      // <-- This pattern causes the crash
    with PureBuilder.Step0[F, Ctx]
    with ParallelBuilder.Step0[F, Ctx]
    with ForEachBuilder.Step0[F, Ctx]
```

## Root Cause

Scala 3's type system has stricter requirements for trait linearization. When a trait extends a path-dependent type (like `ObjectName.TraitName[TypeParams]`) with higher-kinded type parameters, the compiler's `baseData` computation fails because it expects class parents but receives an `AppliedType`.

This appears to be a limitation/bug in Scala 3 when dealing with:
- Path-dependent types from companion objects
- Higher-kinded type parameters (`F[_]`)
- Multiple inheritance through `with` clauses

## Solution: Move Traits to Package Level

Refactor the nested `Step0` traits to be top-level traits with unique names:

### After (compiles successfully):

```scala
// RunIOBuilder.scala
trait RunIOBuilderStep0[F[_], Ctx <: WorkflowContext] {
  def runIO[Input] = new RunIOBuilderStep1[Input]
  // ...
}

object RunIOBuilder {
  // Keep type alias for backward compatibility if needed
  type Step0[F[_], Ctx <: WorkflowContext] = RunIOBuilderStep0[F, Ctx]
}

// AllBuilders.scala
trait AllBuilders[F[_], Ctx <: WorkflowContext]
    extends WIOBuilderMethods[F, Ctx]
    with HandleSignalBuilderStep0[F, Ctx]
    with LoopBuilderStep0[F, Ctx]
    with AwaitBuilderStep0[F, Ctx]
    with ForkBuilderStep0[F, Ctx]
    with BranchBuilderStep0[F, Ctx]
    with DraftBuilderStep0[F, Ctx]
    with RunIOBuilderStep0[F, Ctx]
    with PureBuilderStep0[F, Ctx]
    with ParallelBuilderStep0[F, Ctx]
    with ForEachBuilderStep0[F, Ctx]
```

## Affected Files

The following builder files need to be refactored:

- `workflows4s-core/src/main/scala/workflows4s/wio/builders/RunIOBuilder.scala`
- `workflows4s-core/src/main/scala/workflows4s/wio/builders/HandleSignalBuilder.scala`
- `workflows4s-core/src/main/scala/workflows4s/wio/builders/LoopBuilder.scala`
- `workflows4s-core/src/main/scala/workflows4s/wio/builders/AwaitBuilder.scala`
- `workflows4s-core/src/main/scala/workflows4s/wio/builders/ForkBuilder.scala`
- `workflows4s-core/src/main/scala/workflows4s/wio/builders/BranchBuilder.scala`
- `workflows4s-core/src/main/scala/workflows4s/wio/builders/DraftBuilder.scala`
- `workflows4s-core/src/main/scala/workflows4s/wio/builders/PureBuilder.scala`
- `workflows4s-core/src/main/scala/workflows4s/wio/builders/ParallelBuilder.scala`
- `workflows4s-core/src/main/scala/workflows4s/wio/builders/ForEachBuilder.scala`
- `workflows4s-core/src/main/scala/workflows4s/wio/builders/AllBuilders.scala`

## Context

This issue was discovered while adding the `F[_]` effect type parameter to abstract workflows4s from Cats Effect to support multiple effect systems (IO, ZIO, Future, Id).

The original code without `F[_]` type parameters compiled successfully because the nested trait pattern works when:
- Type parameters are simple (like `Ctx <: WorkflowContext`)
- But fails when higher-kinded types (`F[_]`) are added

## Scala Version

- Scala 3.7.3
- sbt 1.11.7

## Minimal Reproduction (Scastie)

Run this in [Scastie](https://scastie.scala-lang.org/) with Scala 3.7.3:

```scala
//> using scala 3.7.3

// Nested traits inside objects with F[_] type parameter
object BuilderA {
  trait Step0[F[_]] {
    class Inner[In] {
      def apply(f: In => F[Int]): F[Int] = ???
    }
    def methodA[In] = new Inner[In]
  }
}

object BuilderB {
  trait Step0[F[_]] {
    def methodB: String = "B"
  }
}

// Trait extending multiple nested traits with F[_]
trait AllBuilders[F[_]]
    extends BuilderA.Step0[F]
    with BuilderB.Step0[F]

// Context trait with nested object extending AllBuilders
trait Context {
  type Eff[_]

  // This pattern triggers the crash
  object API extends AllBuilders[Eff]
}

// Concrete implementation
object MyContext extends Context {
  type Eff[A] = A  // Identity
}

// Using the API triggers the crash during compilation
object Test {
  import MyContext.*

  val result = API.methodA[String](s => s.length)
}
```

This crashes with:
```
java.lang.AssertionError: assertion failed: trait AllBuilders has non-class parent: AppliedType(...)
```

### Key Pattern That Triggers the Crash

1. **Nested traits in objects** with higher-kinded type parameter `F[_]`
2. **Combined trait** (`AllBuilders`) extending multiple such nested traits
3. **Object inside a trait** (`object API extends AllBuilders[Eff]`) where `Eff` is an abstract type member
4. **Usage of methods** from the builders through the object

## Related

This may be related to known Scala 3 issues with path-dependent types and type linearization. Consider filing a bug report at https://github.com/scala/scala3/issues if not already reported.
