# workflows4s-macros

Macro utilities for effect type extraction.

## Goal

Remove explicit `F[_]` type parameter from WIO nodes by extracting it from `Ctx` at compile time.

**Current approach (explicit F):**
```scala
case class RunIO[Ctx, F[_], In, Out](buildIO: In => F[Out])
```

**Desired approach:**
```scala
case class RunIO[Ctx, In, Out](buildIO: In => ???)
// where ??? somehow derives F from Ctx
```

## Solution: Transparent Inline

Using `transparent inline`, the macro extracts AND preserves type refinement:

```scala
trait TestContext { type F[_] }
object MyCtx extends TestContext { type F[A] = IO[A] }

// Macro extracts F = IO at compile time with transparent inline
val he = summon[HasEffect[MyCtx.type]]

// This compiles WITHOUT casts!
val value: he.F[Int] = IO.pure(42)
```

The key is `transparent inline given` instead of regular `inline given` - it preserves the refined return type.

## Approaches Explored

| Approach | Works | Trade-offs |
|----------|-------|------------|
| Type Projection (`Ctx#F`) | ❌ | Unsound, dropped in Scala 3 |
| Match Types + HKT | ❌ | Can't extract HKT params |
| Regular Inline Macros | ⚠️ | F is existential, not unified |
| Transparent Inline Macros | ✅ | Preserves type refinement! |
| Explicit F Parameter | ✅ | Verbose but type-safe |

## Running the Demos

```bash
# Regular inline (existential types - doesn't unify)
sbt "workflows4s-macros/runMain workflows4s.macros.demo"

# Transparent inline (preserves refinement - works!)
sbt "workflows4s-macros/runMain workflows4s.macros.transparentDemo"
```

## Files

- `EffectExtraction.scala` - Regular inline macro (existential types)
- `TransparentApproach.scala` - Transparent inline macro (preserves refinement)
- `UsabilityComparison.scala` - API comparison across approaches
