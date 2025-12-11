package workflows4s.macros

import scala.quoted.*

/** Exploring transparent inline to preserve type refinement.
  *
  * The idea: `transparent inline` can return a more specific type than declared.
  * Maybe we can return `HasEffect[Ctx] { type F[A] = IO[A] }` instead of just `HasEffect[Ctx]`.
  */
object TransparentApproach {

  trait HasEffect[Ctx] {
    type F[_]
  }

  object HasEffect {
    // Using transparent inline - return type can be refined
    transparent inline given derived[Ctx]: HasEffect[Ctx] = ${ derivedImpl[Ctx] }

    private def derivedImpl[Ctx: Type](using Quotes): Expr[HasEffect[Ctx]] = {
      import quotes.reflect.*

      val ctxRepr = TypeRepr.of[Ctx]
      val fSymbol = ctxRepr.typeSymbol.typeMember("F")
      val fType = ctxRepr.memberType(fSymbol)

      val actualType = fType match {
        case TypeBounds(lo, hi) if lo =:= hi => lo
        case other                           => other
      }

      actualType match {
        case tl: TypeLambda if tl.paramNames.size == 1 =>
          tl.asType match {
            case '[type f[a]; f] =>
              // Try to return a refined type
              // The question: will transparent inline preserve this refinement?
              '{
                new HasEffect[Ctx] {
                  type F[A] = f[A]
                }
              }
            case _ =>
              report.errorAndAbort(s"Could not capture HKT: ${tl.show}")
          }
        case _ =>
          report.errorAndAbort(s"F must be [_] =>> ..., got: ${actualType.show}")
      }
    }
  }
}
