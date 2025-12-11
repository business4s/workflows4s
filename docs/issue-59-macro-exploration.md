Following up on the macro exploration - found a working solution.

> AI-assisted research, details may need verification.

## Problem

Regular inline macros produce existential types - `he.F` doesn't unify with `IO`:

```scala
val he = summon[HasEffect[IOCtx.type]]
val value: he.F[Int] = IO.pure(42)  // Error: Found IO[Int], Required: he.F[Int]
```

## Solution

`transparent inline` + non-inline `using` parameter:

```scala
transparent inline def runIO[Ctx, In, Out](using
    he: HasEffect[Ctx]  // NOT inline - needed for path-dependent type
)(f: In => he.F[Out]): RunIO[Ctx, In, Out]
```

This works because:
- `transparent inline` preserves type refinement (whitebox macro equivalent)
- Non-inline `he` can be used as stable path for `he.F[Out]`

## Result

```scala
// Compiles
WIO.runIO[TestCtx.type, String, Int](s => IO.pure(s.length))

// Correctly rejected
WIO.runIO[TestCtx.type, String, Int](s => Option(s.length))
// Error: Found Option[Int], Required: IO[Int]
```

Code on `prototype/macro-effect-extraction`:
```bash
sbt "workflows4s-macros/runMain workflows4s.macros.contextFunctionDemo"
```

@Krever thoughts?
