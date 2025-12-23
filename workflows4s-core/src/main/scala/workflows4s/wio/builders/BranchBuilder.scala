package workflows4s.wio.builders

import cats.implicits.catsSyntaxOptionId
import workflows4s.wio.*
import workflows4s.wio.model.ModelUtils

trait BranchBuilderStep0[F[_], Ctx <: WorkflowContext] {

  def branch[In]: BranchBuilderStep1[In] = BranchBuilderStep1()

  case class BranchBuilderStep1[In]() {

    def when[Err, Out <: WCState[Ctx]](cond: In => Boolean)(wio: WIO[F, In, Err, Out, Ctx]): Step2[In, Err, Out] =
      Step2(x => Option.when(cond(x))(x), wio, None)

    def create[T, Err, Out <: WCState[Ctx]](cond: In => Option[T])(wio: WIO[F, T, Err, Out, Ctx]): Step2[T, Err, Out] =
      Step2(cond, wio, None)

    case class Step2[T, Err, Out <: WCState[Ctx]](cond: In => Option[T], wio: WIO[F, T, Err, Out, Ctx], name: Option[String]) {

      def named(name: String): Step2[T, Err, Out]                   = this.copy(name = name.some)
      def autoNamed()(using n: sourcecode.Name): Step2[T, Err, Out] = named(ModelUtils.prettifyName(n.value))

      def done: WIO.Branch[F, In, Err, Out, Ctx, ?] = WIO.Branch(cond, wio, name)
    }

  }

}

object BranchBuilder {
  type Step0[F[_], Ctx <: WorkflowContext] = BranchBuilderStep0[F, Ctx]
}
