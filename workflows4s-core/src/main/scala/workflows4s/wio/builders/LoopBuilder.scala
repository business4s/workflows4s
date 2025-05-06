package workflows4s.wio.builders

import workflows4s.wio.*

import scala.util.chaining.scalaUtilChainingOps

object LoopBuilder {

  trait Step0[Ctx <: WorkflowContext]() {

    def repeat[In <: WCState[Ctx], Err, Out <: WCState[Ctx]](action: WIO[In, Err, Out, Ctx]): LoopBuilderStep1[Err, Out, In] = LoopBuilderStep1(
      action,
    )

    case class LoopBuilderStep1[Err, BodyOut <: WCState[Ctx], BodyIn <: WCState[Ctx]](private val repeatAction: WIO[BodyIn, Err, BodyOut, Ctx]) {
      def untilSome[Out <: WCState[Ctx]](f: BodyOut => Option[Out]): Step2[BodyOut, Out]                       = Step2(x => f(x).toRight(x))
      def until(f: BodyOut => Boolean): Step2[BodyOut, BodyOut]                                                = Step2(x => Either.cond(f(x), x, x))
      def untilRight[ReturnIn, Out <: WCState[Ctx]](f: BodyOut => Either[ReturnIn, Out]): Step2[ReturnIn, Out] = Step2(f)

      // TODO the builder could be more typesafe, disallow setting already setup values
      case class Step2[ReturnIn, Out <: WCState[Ctx]](
          private val releaseCondition: BodyOut => Either[ReturnIn, Out],
      ) {

        def onRestart(action: WIO[ReturnIn, Err, BodyIn, Ctx]): Step3 = Step3(action)

        def onRestartDoNothing(using ev1: ReturnIn <:< BodyOut, ev2: BodyOut <:< BodyIn): Step3 = Step3(
          WIO.build[Ctx].pure.makeFrom[ReturnIn].value(_.pipe(ev1.apply).pipe(ev2.apply)).done,
        )

        case class Step3(
            private val onRestart: WIO[ReturnIn, Err, BodyIn, Ctx],
            private val releaseBranchName: Option[String] = None,
            private val restartBranchName: Option[String] = None,
            private val conditionName: Option[String] = None,
        ) {

          def named(conditionName: String = null, releaseBranchName: String = null, restartBranchName: String = null): WIO[BodyIn, Err, Out, Ctx] =
            this
              .copy(
                releaseBranchName = Option(releaseBranchName).orElse(this.releaseBranchName),
                restartBranchName = Option(restartBranchName).orElse(this.restartBranchName),
                conditionName = Option(conditionName).orElse(this.conditionName),
              )
              .done

          def done: WIO[BodyIn, Err, Out, Ctx] = {
            val meta = WIO.Loop.Meta(
              releaseBranchName = releaseBranchName,
              restartBranchName = restartBranchName,
              conditionName = conditionName,
            )
            WIO.Loop(repeatAction, releaseCondition, onRestart, WIO.Loop.State.Forward(repeatAction), meta, Vector.empty)
          }

        }

      }

    }

  }

}
