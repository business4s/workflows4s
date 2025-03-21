package workflows4s.wio.builders

import scala.jdk.DurationConverters.*

import cats.implicits.catsSyntaxOptionId
import workflows4s.wio.*
import workflows4s.wio.model.ModelUtils

object ForkBuilder {

  trait Step0[Ctx <: WorkflowContext]() {

    def fork[In]: ForkBuilder[In, Nothing, Nothing] = ForkBuilder(Vector(), None)

    case class ForkBuilder[-In, +Err, +Out <: WCState[Ctx]](branches: Vector[WIO.Branch[In, Err, Out, Ctx, ?]], name: Option[String]) {
      def onSome[T, Err1 >: Err, Out1 >: Out <: WCState[Ctx], In1 <: In](cond: In1 => Option[T])(
          wio: => WIO[T, Err1, Out1, Ctx],
          name: String = null,
      ): ForkBuilder[In1, Err1, Out1] = addBranch(WIO.Branch(cond, () => wio, Option(name)))

      // here we can add some APIs for exhaustive handling of Booleans or Eithers.

      def matchCondition[T, Err1 >: Err, Out1 >: Out <: WCState[Ctx], In1 <: In](condition: In1 => Boolean, name: String = null)(
          onTrue: WIO[In1, Err1, Out1, Ctx],
          onFalse: WIO[In1, Err1, Out1, Ctx],
      ): WIO[In1, Err1, Out1, Ctx] = WIO.Fork(
        Vector(
          WIO.build[Ctx].branch[In1].when(condition)(onTrue).named("Yes").done,
          WIO.build[Ctx].branch[In1].when(condition.andThen(!_))(onFalse).named("No").done,
        ),
        Option(name),
        None,
      )

      def addBranch[T, Err1 >: Err, Out1 >: Out <: WCState[Ctx], In1 <: In](
          b: WIO.Branch[In1, Err1, Out1, Ctx, ?],
      ): ForkBuilder[In1, Err1, Out1] = this.copy(branches = branches.appended(b))

      def named(name: String): ForkBuilder[In, Err, Out]                   = this.copy(name = name.some)
      def autoNamed()(using n: sourcecode.Name): ForkBuilder[In, Err, Out] = named(ModelUtils.prettifyName(n.value))

      def done: WIO[In, Err, Out, Ctx] = WIO.Fork(branches, name, None)
    }

  }

}
