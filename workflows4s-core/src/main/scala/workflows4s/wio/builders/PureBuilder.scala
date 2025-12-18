package workflows4s.wio.builders

import cats.implicits.catsSyntaxEitherId
import workflows4s.wio.model.ModelUtils
import workflows4s.wio.{ErrorMeta, WCState, WIO, WorkflowContext}

trait PureBuilderStep0[F[_], Ctx <: WorkflowContext] {

  def pure: Step1 = Step1()

  case class Step1() {

    def apply[O <: WCState[Ctx]](value: => O): Step2[Any, Nothing, O] = Step2[Any, Nothing, O](_ => Right(value), None)

    def error[Err](value: => Err)(using em: ErrorMeta[Err]): Step2[Any, Err, Nothing] = Step2[Any, Err, Nothing](_ => Left(value), None)

    def makeFrom[In]: MakeStep[In] = MakeStep[In]()

    case class MakeStep[In]() {
      def apply[Err, Out <: WCState[Ctx]](f: In => Either[Err, Out])(using em: ErrorMeta[Err]): Step2[In, Err, Out] = Step2(f, None)

      def value[O <: WCState[Ctx]](f: In => O): Step2[In, Nothing, O] = Step2[In, Nothing, O](f.andThen(_.asRight), None)

      def error[Err](f: In => Err)(using em: ErrorMeta[Err]): Step2[In, Err, Nothing] = Step2[In, Err, Nothing](f.andThen(_.asLeft), None)

      def errorOpt[Err, Out >: In <: WCState[Ctx]](f: In => Option[Err])(using em: ErrorMeta[Err]): Step2[In, Err, Out] =
        Step2[In, Err, Out](s => f(s).map(Left(_)).getOrElse(Right(s: In)), None)

    }

    case class Step2[In, Err, Out <: WCState[Ctx]](f: In => Either[Err, Out], name: Option[String])(using em: ErrorMeta[Err]) {

      def named(name: String): WIO[F, In, Err, Out, Ctx] = this.copy(name = Some(name)).done

      def autoNamed(using name: sourcecode.Name): WIO[F, In, Err, Out, Ctx] = this.copy(name = Some(ModelUtils.prettifyName(name.value))).done

      def done: WIO[F, In, Err, Out, Ctx] = WIO.Pure(f, WIO.Pure.Meta(em, name))
    }
  }

}

object PureBuilder {
  type Step0[F[_], Ctx <: WorkflowContext] = PureBuilderStep0[F, Ctx]
}
