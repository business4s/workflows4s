//> using scala 3.3.4

import scala.quoted.*

// ===========================================
// Macro-based HKT Extraction from Context
// ===========================================

// Our context with a higher-kinded type member
trait WorkflowContext {
  type F[_]
  type State
  type Event
}

// A wrapper that holds the extracted F type
// This is what the macro will produce
trait ExtractedEffect[C <: WorkflowContext] {
  type F[_]
}

object ExtractedEffect {
  // The macro that extracts F from a concrete WorkflowContext
  inline given extract[C <: WorkflowContext]: ExtractedEffect[C] = ${ extractImpl[C] }

  def extractImpl[C <: WorkflowContext: Type](using Quotes): Expr[ExtractedEffect[C]] = {
    import quotes.reflect.*

    val ctxType = TypeRepr.of[C]

    // Find the F type member in C
    val fMember = ctxType.typeSymbol.typeMember("F")

    if fMember.isNoSymbol then
      report.errorAndAbort(s"Type ${ctxType.show} does not have a type member F")

    // Get the actual type of F as defined in C
    val fType = ctxType.memberType(fMember)

    // fType is a TypeLambda like [A] =>> IO[A]
    // We need to extract the type constructor
    fType match {
      case TypeLambda(_, _, body) =>
        // body is the result type, e.g., IO[A] where A is a param ref
        // We need to get the type constructor (IO)
        body match {
          case AppliedType(tycon, _) =>
            // tycon is the type constructor we want
            tycon.asType match {
              case '[t] =>
                // Now we have the type constructor, but as a simple type
                // We need to reconstruct it as F[_]
                '{
                  new ExtractedEffect[C] {
                    type F[A] = t
                  }
                }
            }
          case other =>
            // F might be abstract or a simple type alias
            report.errorAndAbort(s"Cannot extract type constructor from ${other.show}")
        }
      case other =>
        report.errorAndAbort(s"F is not a type lambda: ${other.show}")
    }
  }
}

// Helper type alias to use the extracted F
type EffectOf[C <: WorkflowContext] = ExtractedEffect[C]#F

// ===========================================
// Usage Example
// ===========================================

// A simple effect for testing
case class IO[A](run: () => A) {
  def map[B](f: A => B): IO[B] = IO(() => f(run()))
  def flatMap[B](f: A => IO[B]): IO[B] = f(run())
}

object IO {
  def pure[A](a: A): IO[A] = IO(() => a)
}

// Concrete workflow context
object MyWorkflow extends WorkflowContext {
  type F[A] = IO[A]
  type State = String
  type Event = String
}

// Now we can try to use the extracted type
// Note: This still uses type projection which is problematic
// def example: EffectOf[MyWorkflow.type][Int] = IO.pure(42)

@main def demo(): Unit = {
  // Test the macro extraction
  val extracted = summon[ExtractedEffect[MyWorkflow.type]]
  println(s"Extracted effect type holder: $extracted")

  // The challenge: how do we USE extracted.F in type signatures?
  // At runtime it works, but at compile-time we hit type projection limits

  println("Macro can extract the type at compile time")
  println("But using it in type signatures still requires type projection")
}
