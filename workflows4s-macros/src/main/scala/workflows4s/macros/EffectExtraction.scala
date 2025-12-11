package workflows4s.macros

import scala.quoted.*

/** Macro utilities for extracting the effect type F[_] from a context type.
  *
  * Goal: Remove explicit F type parameter from WIO nodes by deriving it from Ctx.
  *
  * Current approach (explicit F):
  *   case class RunIO[Ctx, F[_], In, Out](buildIO: In => F[Out])
  *
  * Desired approach (derived F):
  *   case class RunIO[Ctx, In, Out](buildIO: In => ??? )
  *   where ??? somehow gets F from Ctx
  */
object EffectExtraction {

  /** Type class witnessing that Ctx has an effect type F[_] */
  trait HasEffect[Ctx] {
    type F[_]
  }

  object HasEffect {
    /** Aux pattern to expose F as a type parameter */
    type Aux[Ctx, F0[_]] = HasEffect[Ctx] { type F[A] = F0[A] }

    /** Inline macro to derive HasEffect for any type with F[_] member */
    inline given derived[Ctx]: HasEffect[Ctx] = ${ derivedImpl[Ctx] }

    private def derivedImpl[Ctx: Type](using Quotes): Expr[HasEffect[Ctx]] = {
      import quotes.reflect.*

      val ctxRepr = TypeRepr.of[Ctx]
      val fSymbol = ctxRepr.typeSymbol.typeMember("F")

      if fSymbol.isNoSymbol then
        report.errorAndAbort(s"Type ${ctxRepr.show} does not have a type member named 'F'")

      val fType = ctxRepr.memberType(fSymbol)

      // Handle TypeBounds wrapping a TypeLambda (common for nested objects)
      val actualType = fType match {
        case TypeBounds(lo, hi) if lo =:= hi => lo
        case other                           => other
      }

      actualType match {
        case tl: TypeLambda if tl.paramNames.size == 1 =>
          // F is a type lambda like [A] =>> Option[A]
          // Capture it via asType with HKT pattern
          tl.asType match {
            case '[type f[a]; f] =>
              '{
                new HasEffect[Ctx] {
                  type F[A] = f[A]
                }
              }
            case _ =>
              report.errorAndAbort(s"Could not capture F as HKT from: ${tl.show}")
          }

        case _ =>
          report.errorAndAbort(s"F must be a type lambda with one parameter, got: ${actualType.show}")
      }
    }
  }

  /** Type alias that extracts F from Ctx using type projection.
    *
    * Note: Type projection on abstract types is deprecated in Scala 3.
    * This works when HasEffect[Ctx] is concrete but may cause issues
    * when Ctx is abstract.
    */
  type ExtractF[Ctx] = HasEffect[Ctx]#F

  /** Witness class that holds an HKT type for runtime inspection */
  trait TypeWitness[F[_]] {
    type Effect[A] = F[A]
  }

  /** Transparent inline that returns a TypeWitness for the F type of Ctx */
  transparent inline def effectOf[Ctx]: TypeWitness[?] = ${ effectOfImpl[Ctx] }

  private def effectOfImpl[Ctx: Type](using Quotes): Expr[TypeWitness[?]] = {
    import quotes.reflect.*

    val ctxRepr = TypeRepr.of[Ctx]
    val fSymbol = ctxRepr.typeSymbol.typeMember("F")

    if fSymbol.isNoSymbol then
      report.errorAndAbort(s"Type ${ctxRepr.show} does not have type member 'F'")

    val fType = ctxRepr.memberType(fSymbol)

    // Handle both direct TypeLambda and TypeBounds wrapping a TypeLambda
    val actualType = fType match {
      case TypeBounds(lo, hi) if lo =:= hi => lo // Exact bound, unwrap
      case other                           => other
    }

    actualType match {
      case tl: TypeLambda if tl.paramNames.size == 1 =>
        tl.asType match {
          case '[type f[a]; f] =>
            '{ new TypeWitness[f] {} }
          case _ =>
            report.errorAndAbort(s"Could not capture HKT: ${tl.show}")
        }
      case _ =>
        report.errorAndAbort(s"F is not a TypeLambda: ${actualType.show} (original: ${fType.show})")
    }
  }
}
