package workflows4s.wio.builders

import cats.implicits.catsSyntaxEitherId
import workflows4s.wio.model.ModelUtils
import workflows4s.wio.{ErrorMeta, WCState, WIO, WIOContext, WorkflowContext}

object PureBuilder {

  trait Step0[Ctx <: WorkflowContext]() {

    def pure: Step1 = Step1()

    case class Step1() {

      def apply[O <: WCState[Ctx]](value: WIOContext[WCState[Ctx]] ?=> O): Step2[Any, Nothing, O] =
        Step2[Any, Nothing, O](ctx => _ => Right(value(using ctx)), None)

      def error[Err](value: WIOContext[WCState[Ctx]] ?=> Err)(using em: ErrorMeta[Err]): Step2[Any, Err, Nothing] =
        Step2[Any, Err, Nothing](ctx => _ => Left(value(using ctx)), None)

      def makeFrom[In]: MakeStep[In] = MakeStep[In]()

      case class MakeStep[In]() {
        def apply[Err, Out <: WCState[Ctx]](f: WIOContext[WCState[Ctx]] ?=> In => Either[Err, Out])(using em: ErrorMeta[Err]): Step2[In, Err, Out] =
          Step2(ctx => f(using ctx), None)

        def value[O <: WCState[Ctx]](f: WIOContext[WCState[Ctx]] ?=> In => O): Step2[In, Nothing, O] =
          Step2[In, Nothing, O](ctx => (f(using ctx)).andThen(_.asRight), None)

        def error[Err](f: WIOContext[WCState[Ctx]] ?=> In => Err)(using em: ErrorMeta[Err]): Step2[In, Err, Nothing] =
          Step2[In, Err, Nothing](ctx => (f(using ctx)).andThen(_.asLeft), None)

        def errorOpt[Err, Out >: In <: WCState[Ctx]](f: WIOContext[WCState[Ctx]] ?=> In => Option[Err])(using
            em: ErrorMeta[Err],
        ): Step2[In, Err, Out] =
          Step2[In, Err, Out](ctx => s => (f(using ctx))(s).map(Left(_)).getOrElse(Right(s: In)), None)

      }

      case class Step2[In, Err, Out <: WCState[Ctx]](f: WIOContext[WCState[Ctx]] => In => Either[Err, Out], name: Option[String], description: Option[String] = None)(using
          em: ErrorMeta[Err],
      ) {

        def named(name: String, description: String = null): WIO[In, Err, Out, Ctx] = this.copy(name = Some(name), description = Option(description)).done

        def autoNamed(description: String = null)(using name: sourcecode.Name): WIO[In, Err, Out, Ctx] = this.copy(name = Some(ModelUtils.prettifyName(name.value)), description = Option(description)).done

        def done: WIO[In, Err, Out, Ctx] = WIO.Pure(f, WIO.Pure.Meta(em, name, description))
      }
    }

  }

}
