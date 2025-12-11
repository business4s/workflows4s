Following up on the effect abstraction discussion - I explored whether macros could extract `F[_]` from `Ctx` at compile time, potentially avoiding explicit F parameters on WIO nodes.

> **Note**: This research was AI-assisted, so some details might need verification.

## TL;DR

Found a working solution: `transparent inline` + non-inline `using` parameter enables **type-safe** effect extraction without explicit F parameters.

---

## The Problem

With regular `inline given`, the extracted type is existential - the compiler doesn't unify `he.F` with `IO`:

```scala
val he = summon[HasEffect[IOCtx.type]]
val value: he.F[Int] = IO.pure(42)  // Error: Found IO[Int], Required: he.F[Int]
```

---

## Solution: Transparent Inline + Using Parameter

The key is combining:
1. `transparent inline` on the method (preserves type refinement)
2. Non-inline `using he: HasEffect[Ctx]` (allows path-dependent type `he.F`)

```scala
trait HasEffect[Ctx] { type F[_] }

object HasEffect {
  transparent inline given derived[Ctx]: HasEffect[Ctx] = ${ derivedImpl[Ctx] }
  // macro extracts F from Ctx.F
}

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

---

## Why It Works

- `transparent inline` is Scala 3's equivalent of whitebox macros
- When `HasEffect[Ctx]` is summoned at the call site, the refined type (with `F = IO`) is preserved
- The non-inline `he` parameter can be used as a stable path for the dependent type `he.F[Out]`
- Inline parameters cannot be type prefixes (compiler error), so `he` must be non-inline

---

## What This Enables

Effect-polymorphic WIO nodes without explicit F type parameters:

```scala
// Current (explicit F):
case class RunIO[Ctx, F[_], In, Out](buildIO: In => F[Out])

// New (F derived from Ctx):
case class RunIO[Ctx, In, Out](buildIO: In => Any)  // F erased internally
// Builder enforces type safety via HasEffect
```

---

## Code

On `prototype/macro-effect-extraction` branch in `workflows4s-macros` module:

```bash
# Type-safe builder demo
sbt "workflows4s-macros/runMain workflows4s.macros.contextFunctionDemo"

# Transparent inline basics
sbt "workflows4s-macros/runMain workflows4s.macros.transparentDemo"
```

What do you think @Krever?
