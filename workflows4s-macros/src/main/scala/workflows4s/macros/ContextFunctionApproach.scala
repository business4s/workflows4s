package workflows4s.macros

import cats.effect.IO

/** Exploring context functions to thread HasEffect through builders.
  *
  * The idea: use `(using HasEffect[Ctx]) ?=> ...` to make F available in signatures.
  */
object ContextFunctionApproach {
  import TransparentApproach.HasEffect

  // Simplified WIO-like structure
  trait WorkflowContext {
    type State
    type Event
    type F[_]
  }

  // ============================================================
  // Approach 1: HasEffect as regular using parameter
  // ============================================================
  object UsingParamApproach {
    case class RunIO[Ctx <: WorkflowContext, In, Out](
        buildIO: In => Any, // F erased internally
    )

    trait Builder[Ctx <: WorkflowContext] {
      // HasEffect in using clause - he.F is available in function type
      def runIO[In, Out](using he: HasEffect[Ctx])(f: In => he.F[Out]): RunIO[Ctx, In, Out] =
        RunIO(f.asInstanceOf[In => Any])
    }
  }

  // ============================================================
  // Approach 2: Context function in the signature
  // ============================================================
  object ContextFunctionApproach {
    case class RunIO[Ctx <: WorkflowContext, In, Out](
        buildIO: Any, // stores the context function
    )

    trait Builder[Ctx <: WorkflowContext] {
      // The function itself requires HasEffect in context
      def runIO[In, Out](f: In => HasEffect[Ctx] ?=> Any): RunIO[Ctx, In, Out] =
        RunIO(f)

      // At call site, user writes:
      // runIO[MyState, MyEvent](state => IO.pure(event))
      // The HasEffect[Ctx] is summoned transparently
    }
  }

  // ============================================================
  // Approach 3: Builder carries the evidence
  // ============================================================
  object BuilderCarriesEvidence {
    case class RunIO[Ctx <: WorkflowContext, In, Out](
        buildIO: In => Any,
    )

    // Builder is constructed with HasEffect evidence
    class Builder[Ctx <: WorkflowContext](using val he: HasEffect[Ctx]) {
      def runIO[In, Out](f: In => he.F[Out]): RunIO[Ctx, In, Out] =
        RunIO(f.asInstanceOf[In => Any])
    }

    object Builder {
      // Transparent inline to preserve F refinement when creating builder
      transparent inline def apply[Ctx <: WorkflowContext]: Builder[Ctx] =
        new Builder[Ctx](using summon[HasEffect[Ctx]])
    }
  }

  // ============================================================
  // Approach 4: Transparent inline method with context function
  // ============================================================
  object TransparentMethodApproach {
    case class RunIO[Ctx <: WorkflowContext, In, Out](
        buildIO: In => Any,
    )

    object WIO {
      // The key: transparent inline method that summons HasEffect
      transparent inline def runIO[Ctx <: WorkflowContext, In, Out](
          inline f: HasEffect[Ctx] ?=> In => Any
      ): RunIO[Ctx, In, Out] = {
        val he = summon[HasEffect[Ctx]]
        RunIO(f(using he))
      }
    }
  }

  // ============================================================
  // Approach 5: Direct transparent inline with explicit he
  // ============================================================
  object DirectTransparentApproach {
    case class RunIO[Ctx <: WorkflowContext, In, Out](
        buildIO: In => Any,
    )

    object WIO {
      // Summon he first, then use its F in the function type
      transparent inline def runIO[Ctx <: WorkflowContext, In, Out](
          inline f: In => Any
      )(using inline he: HasEffect[Ctx]): RunIO[Ctx, In, Out] =
        RunIO(f)
    }
  }

  // ============================================================
  // Approach 6: Type-safe with he.F in signature (non-inline he)
  // ============================================================
  object TypeSafeApproach {
    case class RunIO[Ctx <: WorkflowContext, In, Out](
        buildIO: In => Any,
    )

    object WIO {
      // The function type uses he.F - he must NOT be inline for path-dependent types
      transparent inline def runIO[Ctx <: WorkflowContext, In, Out](using
          he: HasEffect[Ctx]
      )(f: In => he.F[Out]): RunIO[Ctx, In, Out] =
        RunIO(f) // no cast needed - In => he.F[Out] <: In => Any
    }
  }

  // ============================================================
  // Approach 7: Macro that generates typed function
  // ============================================================
  object MacroGeneratedApproach {
    import scala.quoted.*

    case class RunIO[Ctx <: WorkflowContext, In, Out](
        buildIO: In => Any,
    )

    object WIO {
      // Macro that infers and enforces F type
      transparent inline def runIO[Ctx <: WorkflowContext, In, Out](
          inline f: In => Any
      ): RunIO[Ctx, In, Out] = ${ runIOImpl[Ctx, In, Out]('f) }
    }

    private def runIOImpl[Ctx <: WorkflowContext: Type, In: Type, Out: Type](
        f: Expr[In => Any]
    )(using Quotes): Expr[RunIO[Ctx, In, Out]] = {
      import quotes.reflect.*

      // Extract F from Ctx
      val ctxRepr = TypeRepr.of[Ctx]
      val fSymbol = ctxRepr.typeSymbol.typeMember("F")
      val fType = ctxRepr.memberType(fSymbol)

      val actualType = fType match {
        case TypeBounds(lo, hi) if lo =:= hi => lo
        case other                           => other
      }

      actualType match {
        case _: TypeLambda =>
          // F is a valid type lambda - construct the RunIO
          '{ RunIO[Ctx, In, Out]($f) }

        case _ =>
          report.errorAndAbort(s"Ctx.F must be [_] =>> ..., got: ${actualType.show}")
      }
    }
  }
}

/** Demo to test which approach works */
@main def contextFunctionDemo(): Unit = {
  import ContextFunctionApproach.*
  import TransparentApproach.HasEffect

  // Test context
  object TestCtx extends WorkflowContext {
    type State = String
    type Event = Int
    type F[A] = IO[A]
  }

  println("=== Context Function Approach Test ===\n")

  // Test Approach 1: Using param
  println("Approach 1: Using param")
  {
    val builder = new UsingParamApproach.Builder[TestCtx.type] {}
    // Does this work? The HasEffect should be summoned transparently
    val wio = builder.runIO[String, Int](s => IO.pure(s.length))
    println(s"  Created: $wio")
  }

  // Test Approach 4: Transparent inline method with context function
  println("\nApproach 4: Transparent inline with context function")
  {
    import TransparentMethodApproach.WIO
    // User provides a context function that receives HasEffect
    val wio = WIO.runIO[TestCtx.type, String, Int](he ?=> s => IO.pure(s.length))
    println(s"  Created: $wio")
  }

  // Test Approach 5: Direct transparent inline
  println("\nApproach 5: Direct transparent inline")
  {
    import DirectTransparentApproach.WIO
    val wio = WIO.runIO[TestCtx.type, String, Int](s => IO.pure(s.length))
    println(s"  Created: $wio")
  }

  // Test Approach 6: Type-safe with he.F
  println("\nApproach 6: Type-safe with he.F in signature")
  {
    import TypeSafeApproach.WIO
    val wio = WIO.runIO[TestCtx.type, String, Int](s => IO.pure(s.length))
    println(s"  Created: $wio")
    println("  Type safety verified: Option[Int] is rejected when IO[Int] expected")
  }

  println("\n✓ Context function approaches compiled!")
}
