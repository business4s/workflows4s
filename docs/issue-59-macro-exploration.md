Following up on the effect abstraction discussion - I explored whether macros could extract `F[_]` from `Ctx` at compile time, potentially avoiding explicit F parameters on WIO nodes.

> **Note**: This research was AI-assisted, so some details might need verification.

## TL;DR

**Update**: Using `transparent inline`, the macro successfully extracts `F[_]` AND preserves type refinement - no casts needed!

---

## The Problem with Regular Inline Macros

With regular `inline given`, the extracted type is existential:

```scala
trait HasEffect[Ctx] { type F[_] }

object HasEffect {
  inline given derived[Ctx]: HasEffect[Ctx] = ${ derivedImpl[Ctx] }
  // ...
}

val he = summon[HasEffect[IOCtx.type]]
val value: he.F[Int] = IO.pure(42)  // Error: Found IO[Int], Required: he.F[Int]
```

The compiler doesn't unify `he.F` with `IO`.

---

## Solution: Transparent Inline

Using `transparent inline`, the compiler preserves type refinement:

```scala
trait HasEffect[Ctx] {
  type F[_]
}

object HasEffect {
  // Key: transparent inline preserves the refined return type
  transparent inline given derived[Ctx]: HasEffect[Ctx] = ${ derivedImpl[Ctx] }

  private def derivedImpl[Ctx: Type](using Quotes): Expr[HasEffect[Ctx]] = {
    import quotes.reflect.*

    val ctxRepr = TypeRepr.of[Ctx]
    val fSymbol = ctxRepr.typeSymbol.typeMember("F")
    val fType = ctxRepr.memberType(fSymbol)

    // Handle TypeBounds wrapping (common for nested objects)
    val actualType = fType match {
      case TypeBounds(lo, hi) if lo =:= hi => lo
      case other => other
    }

    actualType match {
      case tl: TypeLambda if tl.paramNames.size == 1 =>
        tl.asType match {
          case '[type f[a]; f] =>
            '{ new HasEffect[Ctx] { type F[A] = f[A] } }
          case _ =>
            report.errorAndAbort(s"Could not capture HKT: ${tl.show}")
        }
      case _ =>
        report.errorAndAbort(s"F must be [_] =>> ..., got: ${actualType.show}")
    }
  }
}
```

Now this compiles:

```scala
object IOCtx { type F[A] = IO[A] }
object OptionCtx { type F[A] = Option[A] }

val heIO = summon[HasEffect[IOCtx.type]]
val heOpt = summon[HasEffect[OptionCtx.type]]

// These compile WITHOUT casts!
val ioValue: heIO.F[Int] = IO.pure(42)
val optValue: heOpt.F[Int] = Some(42)
```

---

## Remaining Challenge: Type Projection

For builder signatures like:

```scala
def runIO[In, Out](f: In => HasEffect[Ctx]#F[Out])
```

Type projection on abstract types (`Ctx#F` where `Ctx` is a type parameter) is unsound and removed in Scala 3. Need to explore if transparent inline can help here too.

---

## Code

On `prototype/macro-effect-extraction` branch in `workflows4s-macros` module:

```bash
# Regular inline (existential types)
sbt "workflows4s-macros/runMain workflows4s.macros.demo"

# Transparent inline (preserves refinement!)
sbt "workflows4s-macros/runMain workflows4s.macros.transparentDemo"
```

What do you think @Krever?
