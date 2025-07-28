package workflows4s.runtime.instanceengine

import cats.effect.{IO, SyncIO}
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine.PostExecCommand
import workflows4s.wio.*
import workflows4s.wio.model.WIOExecutionProgress

import java.time.Instant
import scala.annotation.unused

trait WorkflowInstanceEngine {

  def queryState[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): IO[WCState[Ctx]]

  def getProgress[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): IO[WIOExecutionProgress[WCState[Ctx]]]

  def getExpectedSignals[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): IO[List[SignalDef[?, ?]]]

  def triggerWakeup[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx]): IO[Option[IO[Either[Instant, WCEvent[Ctx]]]]]

  def handleSignal[Ctx <: WorkflowContext, Req, Resp](
      workflow: ActiveWorkflow[Ctx],
      signalDef: SignalDef[Req, Resp],
      req: Req,
  ): IO[Option[IO[(WCEvent[Ctx], Resp)]]]

  def handleEvent[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx], event: WCEvent[Ctx]): SyncIO[Option[ActiveWorkflow[Ctx]]]

  def onStateChange[Ctx <: WorkflowContext](@unused oldState: ActiveWorkflow[Ctx], @unused newState: ActiveWorkflow[Ctx]): IO[Set[PostExecCommand]]

  def processEvent[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx], event: WCEvent[Ctx]): SyncIO[ActiveWorkflow[Ctx]] = this
    .handleEvent(workflow, event)
    .map(_.getOrElse(workflow))

}

object WorkflowInstanceEngine {
  val builder                       = WorkflowInstanceEngineBuilder
  val basic: WorkflowInstanceEngine = builder
    .withJavaTime()
    .withoutWakeUps
    .withoutRegistering
    .withGreedyEvaluation
    .withLogging
    .get

  enum PostExecCommand {
    case WakeUp
  }
}
