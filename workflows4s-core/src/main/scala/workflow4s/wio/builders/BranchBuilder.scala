package workflow4s.wio.builders

import cats.implicits.catsSyntaxOptionId
import workflow4s.wio.model.ModelUtils
import workflow4s.wio.{WIO, *}

object BranchBuilder {

  trait Step0[Ctx <: WorkflowContext]() {

    def branch[In]: BranchBuilderStep1[In] = BranchBuilderStep1()

    case class BranchBuilderStep1[In]() {

      def when[Err, Out <: WCState[Ctx]](cond: In => Boolean)(wio: WIO[In, Err, Out, Ctx]): Step2[Unit, Err, Out] =
        Step2(cond.andThen(Option.when(_)(())), wio.transformInput((x: (In, Any)) => x._1), None)

      def create[T, Err, Out <: WCState[Ctx]](cond: In => Option[T])(wio: WIO[(In, T), Err, Out, Ctx]): Step2[T, Err, Out] =
        Step2(cond, wio, None)

      case class Step2[T, Err, Out <: WCState[Ctx]](cond: In => Option[T], wio: WIO[(In, T), Err, Out, Ctx], name: Option[String]) {

        def named(name: String): Step2[T, Err, Out]                   = this.copy(name = name.some)
        def autoNamed()(using n: sourcecode.Name): Step2[T, Err, Out] = named(ModelUtils.prettifyName(n.value))

        def done: WIO.Branch[In, Err, Out, Ctx] = WIO.Branch(cond, wio, name)
      }

    }

  }

}
