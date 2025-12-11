package workflows4s.runtime.instanceengine

import workflows4s.effect.Effect
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine.PostExecCommand
import workflows4s.wio.*
import workflows4s.wio.internal.{SignalResult, WakeupResult}
import workflows4s.wio.model.WIOExecutionProgress

import scala.annotation.unused

trait WorkflowInstanceEngine[F[_]] {

  def queryState[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): F[WCState[Ctx]]

  def getProgress[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): F[WIOExecutionProgress[WCState[Ctx]]]

  // TODO this would be better if extractable from progress
  def getExpectedSignals[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): F[List[SignalDef[?, ?]]]

  def triggerWakeup[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): F[WakeupResult[F, WCEvent[Ctx]]]

  def handleSignal[Ctx <: WorkflowContext, Req, Resp](
      workflow: ActiveWorkflow[Ctx],
      signalDef: SignalDef[Req, Resp],
      req: Req,
  ): F[SignalResult[F, WCEvent[Ctx], Resp]]

  def handleEvent[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx], event: WCEvent[Ctx]): F[Option[ActiveWorkflow[Ctx]]]

  def onStateChange[Ctx <: WorkflowContext](@unused oldState: ActiveWorkflow[Ctx], @unused newState: ActiveWorkflow[Ctx]): F[Set[PostExecCommand]]

  def processEvent[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx], event: WCEvent[Ctx])(using E: Effect[F]): F[ActiveWorkflow[Ctx]] =
    E.map(this.handleEvent(workflow, event))(_.getOrElse(workflow))

}

object WorkflowInstanceEngine {

  enum PostExecCommand {
    case WakeUp
  }
}
