package workflows4s.wio

import scala.quoted.*

/** Type class witnessing that a WorkflowContext has an effect type F[_].
  *
  * This is derived via macro from Ctx.F at compile time. Uses `transparent inline` to preserve type refinement.
  */
trait HasEffect[Ctx] {
  type F[_]
}

object HasEffect {

  /** Aux pattern for when you need F as a type parameter */
  type Aux[Ctx, F0[_]] = HasEffect[Ctx] { type F[A] = F0[A] }

  /** Derive HasEffect instance from Ctx.F.
    *
    * Uses `transparent inline` to preserve the type refinement, so `summon[HasEffect[MyCtx]].F` is unified with the concrete F type.
    */
  transparent inline given derived[Ctx]: HasEffect[Ctx] = ${ derivedImpl[Ctx] }

  private def derivedImpl[Ctx: Type](using Quotes): Expr[HasEffect[Ctx]] = {
    import quotes.reflect.*

    val ctxRepr = TypeRepr.of[Ctx]

    // For debugging: uncomment to see macro invocations
    // report.info(s"HasEffect derivation for: ${ctxRepr.show}")

    // Dealias to handle type aliases like TestCtx.Ctx -> WorkflowContext.AUX[...]
    val dealiased = ctxRepr.dealias

    // For structural refinement types like WorkflowContext { type State = St; type Event = Evt; type F[A] = IO[A] },
    // we need to recursively search through nested refinements to find F
    def findFInRefinements(tpe: TypeRepr): Option[TypeRepr] = {
      tpe match {
        case Refinement(parent, "F", TypeBounds(lo, hi)) if lo =:= hi =>
          Some(lo)
        case Refinement(parent, _, _)                                 =>
          findFInRefinements(parent)
        case _                                                        =>
          None
      }
    }

    // First try to find F in refinements (handles structural types)
    findFInRefinements(dealiased) match {
      case Some(fType) =>
        fType match {
          case tl: TypeLambda if tl.paramNames.size == 1 =>
            tl.asType match {
              case '[type f[a]; f] =>
                '{ new HasEffect[Ctx] { type F[A] = f[A] } }
              case _               =>
                report.errorAndAbort(s"Could not capture HKT from refinement: ${tl.show}")
            }
          case _                                         =>
            report.errorAndAbort(s"Refinement F is not a type lambda: ${fType.show}")
        }
      case None        =>
        // Fall back to checking type member directly (for object types like SimpleCtx.type)
        val fSymbol = dealiased.typeSymbol.typeMember("F")

        if fSymbol == Symbol.noSymbol then {
          report.errorAndAbort(
            s"Type ${ctxRepr.show} (dealiased: ${dealiased.show}) does not have a type member F",
          )
        } else {
          val fType = dealiased.memberType(fSymbol)

          // Handle TypeBounds wrapping (common for nested objects)
          val actualType = fType match {
            case TypeBounds(lo, hi) if lo =:= hi => lo
            case other                           => other
          }

          actualType match {
            case tl: TypeLambda if tl.paramNames.size == 1 =>
              tl.asType match {
                case '[type f[a]; f] =>
                  '{ new HasEffect[Ctx] { type F[A] = f[A] } }
                case _               =>
                  report.errorAndAbort(s"Could not capture HKT from ${tl.show}")
              }
            case _                                         =>
              report.errorAndAbort(s"F must be a type lambda [_] =>> ..., got: ${actualType.show}")
          }
        }
    }
  }
}
