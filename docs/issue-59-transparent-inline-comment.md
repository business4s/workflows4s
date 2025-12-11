Following up on the macro exploration - found a solution using `transparent inline`.

## The Problem

Regular inline macros extract F correctly, but the type becomes existential:

```scala
val he = summon[HasEffect[IOCtx.type]]
val value: he.F[Int] = IO.pure(42)  // Error: Found IO[Int], Required: he.F[Int]
```

## The Solution: Transparent Inline

`transparent inline` is Scala 3's equivalent of whitebox macros - it preserves type refinement:

```scala
object HasEffect {
  // Key change: transparent inline
  transparent inline given derived[Ctx]: HasEffect[Ctx] = ${ derivedImpl[Ctx] }
}
```

Now this compiles:

```scala
object IOCtx { type F[A] = IO[A] }

val heIO = summon[HasEffect[IOCtx.type]]
val ioValue: heIO.F[Int] = IO.pure(42)  // Works without casts!
```

## Next Steps

Need to explore how to integrate this into builder signatures. The challenge is type projection on abstract Ctx:

```scala
def runIO[In, Out](f: In => ???)  // How to reference F from Ctx here?
```

Ideas welcome. Code is on `prototype/macro-effect-extraction` branch.
