package workflow4s.wio.builders

import cats.effect.IO
import workflow4s.wio.*
import workflow4s.wio.WIO.HandleSignal
import workflow4s.wio.internal.{EventHandler, SignalHandler}
import workflow4s.wio.model.ModelUtils

import scala.reflect.ClassTag

object LoopBuilder {

  trait Step0[Ctx <: WorkflowContext]() {

    def repeat[Err, Out <: WCState[Ctx]](action: WIO[Out, Err, Out, Ctx]): LoopBuilderStep1[Err, Out] = LoopBuilderStep1(action)

    case class LoopBuilderStep1[Err, LoopOut <: WCState[Ctx]](repeatAction: WIO[LoopOut, Err, LoopOut, Ctx]) {
      def untilSome[Out1 <: WCState[Ctx]](f: LoopOut => Option[Out1]): Step2[Out1] = Step2(f)

      // TODO the builder could be more typesafe, disallow setting allready setup values
      case class Step2[Out <: WCState[Ctx]](
          private val releaseCondition: LoopOut => Option[Out],
          private val onRestart: Option[WIO[LoopOut, Err, LoopOut, Ctx]] = None,
          private val releaseBranchName: Option[String] = None,
          private val restartBranchName: Option[String] = None,
          private val conditionName: Option[String] = None,
      ) {

        def onRestart(action: WIO[LoopOut, Err, LoopOut, Ctx]): Step2[Out] = this.copy(onRestart = Some(action))

        def named(releaseBranchName: String = null, restartBranchName: String = null, conditionName: String = null) = this.copy(
          releaseBranchName = Option(releaseBranchName).orElse(this.releaseBranchName),
          restartBranchName = Option(restartBranchName).orElse(this.restartBranchName),
          conditionName = Option(conditionName).orElse(this.conditionName),
        )

        def done: WIO[LoopOut, Err, Out, Ctx] = {
          val meta = WIO.Loop.Meta(
            releaseBranchName = releaseBranchName,
            restartBranchName = restartBranchName,
            conditionName = conditionName,
          )
          WIO.Loop(repeatAction, releaseCondition, repeatAction, onRestart, meta, false)
        }

      }

    }

  }

}
