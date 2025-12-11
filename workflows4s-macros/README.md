# workflows4s-macros

Macro utilities for effect type extraction experiments.

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

## What the Macro Does

The `HasEffect` macro extracts `F[_]` from any context type:

```scala
trait TestContext { type F[_] }
object MyCtx extends TestContext { type F[A] = IO[A] }

// Macro extracts F = IO at compile time
val he = summon[HasEffect[MyCtx.type]]
// he.F is now IO
```

## The Fundamental Limitation

The macro **works** - it correctly extracts `F[_]` from `Ctx`. However:

1. **Existential Types**: The extracted `he.F` is treated as existential by the compiler
2. **No Unification**: `he.F[Int]` is NOT unified with `IO[Int]`
3. **Aux Pattern Required**: To unify them, you need `HasEffect.Aux[Ctx, IO]` which brings back explicit `F`

This is a Scala type system limitation, not a macro limitation.

## Approaches Explored

| Approach | Works | Trade-offs |
|----------|-------|------------|
| Type Projection (`Ctx#F`) | ❌ | Unsound, dropped in Scala 3 |
| Match Types + HKT | ❌ | Can't extract HKT params |
| Macros + HasEffect | ✅ | F is existential, not unified |
| Macros + Casts | ✅ | Unsafe, needs evidence at every use |
| Explicit F Parameter | ✅ | Verbose but type-safe |

## Running the Demo

```bash
sbt "workflows4s-macros/runMain workflows4s.macros.demo"
```

## Conclusion

The explicit `F[_]` type parameter approach remains the cleanest solution. Macros can extract the type but can't make Scala's type system treat the extracted type as equal to the concrete type without unsafe casts.
