Following up on the macro exploration - found a working solution combining `transparent inline` with context functions.

## The Problem

Regular inline macros extract F correctly, but the type becomes existential:

```scala
val he = summon[HasEffect[IOCtx.type]]
val value: he.F[Int] = IO.pure(42)  // Error: Found IO[Int], Required: he.F[Int]
```

## Solution: Transparent Inline + Non-inline Using Parameter

The key is combining:
1. `transparent inline` on the method (preserves type refinement)
2. Non-inline `using he: HasEffect[Ctx]` (allows path-dependent type `he.F`)

```scala
object WIO {
  transparent inline def runIO[Ctx <: WorkflowContext, In, Out](using
      he: HasEffect[Ctx]  // NOT inline - needed for path-dependent type
  )(f: In => he.F[Out]): RunIO[Ctx, In, Out] =
    RunIO(f.asInstanceOf[In => Any])
}
```

Usage:
```scala
object TestCtx extends WorkflowContext {
  type F[A] = IO[A]
}

// This compiles - type safety preserved!
val wio = WIO.runIO[TestCtx.type, String, Int](s => IO.pure(s.length))

// This fails to compile - wrong effect type!
val bad = WIO.runIO[TestCtx.type, String, Int](s => Option(s.length))
// Error: Found Option[Int], Required: cats.effect.IO[Int]
```

## Why It Works

- `transparent inline` is Scala 3's equivalent of whitebox macros
- When `HasEffect[Ctx]` is summoned at the call site, the refined type (with `F = IO`) is preserved
- The non-inline `he` parameter can be used as a stable path for the dependent type `he.F[Out]`

## What This Enables

Effect-polymorphic WIO nodes without explicit F type parameters:

```scala
// Current (explicit F):
case class RunIO[Ctx, F[_], In, Out](buildIO: In => F[Out])

// New (F derived from Ctx):
case class RunIO[Ctx, In, Out](buildIO: In => Any)  // F erased internally
// But builder enforces type safety via HasEffect
```

Code is on `prototype/macro-effect-extraction` branch. Run:
```bash
sbt "workflows4s-macros/runMain workflows4s.macros.contextFunctionDemo"
```
