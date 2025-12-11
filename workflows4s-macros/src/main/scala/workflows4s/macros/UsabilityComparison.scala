package workflows4s.macros

import cats.effect.IO

/** Comparison of how different approaches affect user-facing API.
  *
  * Based on actual usage from WithdrawalWorkflow.scala:
  * ```
  * WIO.runIO[WithdrawalData.Initiated](state =>
  *   service.calculateFees(state.amount).map(WithdrawalEvent.FeeSet.apply)
  * )
  * ```
  */
object UsabilityComparison {

  // ============================================================
  // CURRENT: Hardcoded IO
  // ============================================================
  object CurrentApproach {
    trait WorkflowContext {
      type State
      type Event
      // No F - hardcoded to IO
    }

    case class RunIO[Ctx <: WorkflowContext, In, Out](
        buildIO: In => IO[Out], // IO is hardcoded
    )

    // User writes:
    // WIO.runIO[MyState](state => IO(MyEvent()))
    //
    // ✓ Clean API
    // ✗ Locked to cats-effect IO
  }

  // ============================================================
  // APPROACH 1: Explicit F parameter on WorkflowContext
  // ============================================================
  object ExplicitFApproach {
    trait WorkflowContext {
      type State
      type Event
      type F[_] // User defines this
    }

    // F appears in WIO node signatures
    case class RunIO[Ctx <: WorkflowContext, F[_], In, Out](
        buildIO: In => F[Out],
    )

    // But builders hide it! F comes from ctx.F
    trait RunIOBuilder[Ctx <: WorkflowContext] {
      // User just writes the same thing:
      // WIO.runIO[MyState](state => IO(MyEvent()))
      //
      // The builder knows F from WorkflowContext
    }

    // User defines their context:
    // object MyWorkflow extends WorkflowContext {
    //   type F[A] = IO[A]  // or ZIO[Any, Throwable, A], etc.
    //   ...
    // }
    //
    // Usage is IDENTICAL to current:
    // WIO.runIO[MyState](state => IO(MyEvent()))
    //
    // ✓ Same clean API for workflow authors
    // ✓ Effect-polymorphic
    // ✗ More complex internal types
  }

  // ============================================================
  // APPROACH 2: Hidden casts with HasEffect
  // ============================================================
  object HiddenCastApproach {
    import EffectExtraction.*

    trait WorkflowContext {
      type State
      type Event
      type F[_]
    }

    // F is hidden - stored as Any internally
    case class RunIO[Ctx <: WorkflowContext, In, Out](
        private val buildIO: In => Any, // F erased
    ) {
      def getBuildIO(using he: HasEffect[Ctx]): In => he.F[Out] =
        buildIO.asInstanceOf[In => he.F[Out]]
    }

    // Builder uses HasEffect to construct
    trait RunIOBuilder[Ctx <: WorkflowContext] {
      // Requires HasEffect evidence
      def runIO[In, Out](f: In => HasEffect[Ctx]#F[Out])(using HasEffect[Ctx]): RunIO[Ctx, In, Out] =
        RunIO(f.asInstanceOf[In => Any])
    }

    // User writes:
    // WIO.runIO[MyState](state => IO(MyEvent()))
    //
    // Same API IF HasEffect[Ctx] is in scope (derived from WorkflowContext)
    //
    // ✓ F hidden from type signatures
    // ✗ Type projection (HasEffect[Ctx]#F) - deprecated
    // ✗ Casts can fail at runtime if misused
    // ✗ IDE may not infer types as well
  }

  // ============================================================
  // SUMMARY
  // ============================================================
  /*
  For the USER writing workflows, all approaches look the same:

  ```scala
  object MyWorkflow extends WorkflowContext {
    type F[A] = IO[A]  // Define effect type once
    type State = MyState
    type Event = MyEvent

    val workflow = WIO
      .runIO[MyState](state => IO(MyEvent()))  // Same API!
      .handleEvent((s, e) => newState)
      .autoNamed()
  }
  ```

  The difference is:

  | Approach      | Internal Complexity | Type Safety | IDE Support |
  |---------------|---------------------|-------------|-------------|
  | Current (IO)  | Low                 | ✓           | Excellent   |
  | Explicit F    | Medium              | ✓           | Good        |
  | Hidden Casts  | High                | ⚠️          | Fair        |

  RECOMMENDATION: Explicit F parameter
  - User-facing API is identical
  - Fully type-safe
  - Good IDE support
  - Internal complexity is manageable
  - No deprecated features (type projection)
  */

  def main(args: Array[String]): Unit = {
    println("""
=== Usability Comparison ===

For workflow AUTHORS, all approaches have the SAME API:

  WIO.runIO[MyState](state => IO(MyEvent()))
      .handleEvent((s, e) => newState)
      .autoNamed()

The F parameter is hidden by the builder pattern - it comes from
WorkflowContext.F which the user defines once.

The difference is INTERNAL complexity and type safety:

| Approach      | Type Safety | IDE Support | Deprecated Features |
|---------------|-------------|-------------|---------------------|
| Current (IO)  | ✓           | Excellent   | None                |
| Explicit F    | ✓           | Good        | None                |
| Hidden Casts  | ⚠️ (casts)  | Fair        | Type projection     |

RECOMMENDATION: Explicit F parameter approach
- Same clean API for users
- Fully type-safe internally
- No deprecated Scala 3 features
""")
  }
}
