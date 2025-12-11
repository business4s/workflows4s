Hey @Krever,

I've been prototyping the effect abstraction on the `prototype/effect-in-context` branch and did some research on how we might propagate `F[_]` through `WorkflowContext`. Wanted to share findings and get your input on the direction.

> **Heads up**: This research was AI-assisted, so some details might need verification.

---

## Current Implementation

Following @lbialy's suggestion, I went with a minimal custom typeclass rather than pulling in cats-effect typeclasses:

```scala
trait Effect[F[_]] {
  def pure[A](a: A): F[A]
  def flatMap[A, B](fa: F[A])(f: A => F[B]): F[B]
  def map[A, B](fa: F[A])(f: A => B): F[B]
  def raiseError[A](e: Throwable): F[A]
  def handleErrorWith[A](fa: F[A])(f: Throwable => F[A]): F[A]
  def sleep(duration: FiniteDuration): F[Unit]
  def realTimeInstant: F[Instant]
  def delay[A](a: => A): F[A]
  def liftIO[A](io: IO[A]): F[A]
  def toIO[A](fa: F[A]): IO[A]
  // ... derived ops
}
```

`WorkflowContext` now has `type F[_]` and a `given effect: Effect[F]`:

```scala
trait WorkflowContext {
  type Event
  type State
  type F[_]
  given effect: Effect[F] = deferred

  object WIO extends AllBuilders[Ctx, F](using effect) { ... }
}
```

This works, but `F` has to be explicitly threaded through all WIO nodes and builders as a separate type parameter. I looked into whether we could extract `F` directly from `Ctx` to clean up the signatures.

---

## Approaches for Extracting F from Ctx

### Type Projections (`Ctx#F`) — doesn't work

Type projections on abstract types are [dropped in Scala 3](https://docs.scala-lang.org/scala3/reference/dropped-features/type-projection.html) due to soundness issues. Not coming back.

### Match Types — doesn't work

```scala
type ExtractF[T <: WorkflowContext] = T match {
  case AuxF[f] => f  // "unaccounted type parameter f"
}
```

Scala 3 match types [can't extract higher-kinded type parameters](https://docs.scala-lang.org/scala3/reference/new-types/match-types.html). 

### Path-Dependent Types — doesn't work for our use case

Works when you have a value (`ctx.F[Int]`), but WIO nodes are defined at the type level without a concrete instance.

### Macros — possible but has trade-offs

Scala 3 macros [can actually match HKT](https://docs.scala-lang.org/scala3/guides/macros/reflection.html):

```scala
Type.of[K] match
  case '[type f[X]; f] => Type.of[f]  // This works!
```

Could use this to derive F from Ctx at compile time. Library consumers wouldn't be affected — macros expand during library compilation.

**Trade-offs:**
- (+) Cleaner API, no explicit F threading
- (-) Worse IDE support (IntelliJ shows false errors with advanced macros)
- (-) Harder to debug, cryptic error messages when things break
- (-) More complex codebase to maintain

### Explicit F Parameter — current approach

Thread `F[_]` explicitly through nodes and builders. Verbose but reliable.

---

## Summary

| Approach | Viable | Notes |
|----------|--------|-------|
| Type Projection | ❌ | Unsound, removed in Scala 3 |
| Match Types + HKT | ❌ | Can't extract HKT params |
| Path-Dependent | ❌ | Need value, we only have type |
| Macros | ⚠️ | Works but IDE/debugging concerns |
| Explicit F | ✅ | Current approach, verbose but solid |

---

## Questions

1. Are you okay with the explicit `F` parameter approach, or is the verbosity a concern worth addressing with macros?

2. The `Effect` typeclass has `liftIO`/`toIO` for bridging — does that fit your vision for how ZIO/Kyo/Ox integration would work, or did you have something else in mind?

3. Any thoughts on the typeclass design itself? I kept it minimal but happy to adjust.

Let me know what you think!
