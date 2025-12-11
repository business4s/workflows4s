package workflows4s.macros

/** Exploration: Using casts internally to hide F from public API.
  *
  * The idea: WIO nodes store F-typed values as Any internally,
  * and the macro ensures type safety at the boundaries.
  */
object CastApproach {

  import EffectExtraction.*

  // Simulating a simplified WIO node that needs F
  // Current approach: explicit F
  case class RunIOExplicit[Ctx, F[_], In, Out](
      buildIO: In => F[Out],
  )

  // Cast approach: F hidden, stored as Any
  case class RunIOHidden[Ctx, In, Out](
      private val buildIO: In => Any, // Actually In => F[Out]
  ) {
    // Safe access requires HasEffect evidence
    def getBuildIO(using he: HasEffect[Ctx]): In => he.F[Out] =
      buildIO.asInstanceOf[In => he.F[Out]]
  }

  object RunIOHidden {
    // Constructor that captures F via macro and erases it
    inline def apply[Ctx, In, Out](
        buildIO: In => HasEffect[Ctx]#F[Out],
    )(using he: HasEffect[Ctx]): RunIOHidden[Ctx, In, Out] = {
      // At compile time, we verify the types match
      // At runtime, we erase F to Any
      new RunIOHidden[Ctx, In, Out](buildIO.asInstanceOf[In => Any])
    }
  }

  // Test context
  trait TestCtx { type F[_] }
  object OptionTestCtx extends TestCtx { type F[A] = Option[A] }

  /** Demo showing the cast approach */
  def demo(): Unit = {
    println("\n=== Cast Approach Demo ===\n")

    // With explicit F (current)
    val explicit = RunIOExplicit[OptionTestCtx.type, Option, Int, String](
      (i: Int) => Some(i.toString),
    )
    println(s"Explicit F: ${explicit.buildIO(42)}")

    // With hidden F (cast approach)
    given HasEffect[OptionTestCtx.type] = HasEffect.derived

    val hidden = RunIOHidden[OptionTestCtx.type, Int, String](
      (i: Int) => Some(i.toString),
    )

    // Access requires HasEffect evidence
    val he     = summon[HasEffect[OptionTestCtx.type]]
    val result = hidden.getBuildIO(using he)(42)
    println(s"Hidden F (via cast): $result")

    println("""
Analysis:
- The cast approach DOES hide F from the type signature
- BUT: HasEffect[Ctx]#F still uses type projection (deprecated)
- AND: Every access needs HasEffect evidence
- AND: The erasure is unsafe (casts can fail at runtime)

This is essentially what cats-effect's LiftIO does internally.
The question is whether the API simplification is worth the
added complexity and potential runtime errors.
""")
  }
}
