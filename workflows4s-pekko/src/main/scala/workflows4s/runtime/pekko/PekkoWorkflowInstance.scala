package workflows4s.runtime.pekko

import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.{ActorSystem, RecipientRef}
import org.apache.pekko.util.Timeout
import workflows4s.runtime.pekko.WorkflowBehavior.Command
import workflows4s.runtime.instanceengine.LazyFuture
import workflows4s.runtime.{WorkflowInstance, WorkflowInstanceId}
import workflows4s.wio.model.WIOExecutionProgress
import workflows4s.wio.{SignalDef, WCState, WorkflowContext}

import scala.concurrent.duration.DurationInt

class PekkoWorkflowInstance[Ctx <: WorkflowContext](
    val id: WorkflowInstanceId,
    actorRef: RecipientRef[WorkflowBehavior.Command[Ctx]],
    queryTimeout: Timeout = Timeout(100.millis),
    processingTimeout: Timeout = Timeout(5.seconds),
)(using system: ActorSystem[?])
    extends WorkflowInstance[LazyFuture, WCState[Ctx]] {

  override def queryState(): LazyFuture[WCState[Ctx]] = {
    given Timeout = queryTimeout
    LazyFuture.fromFuture(actorRef.ask(replyTo => Command.QueryState(replyTo)))
  }

  override def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): LazyFuture[Either[WorkflowInstance.UnexpectedSignal, Resp]] = {
    given Timeout = processingTimeout
    LazyFuture.fromFuture(actorRef.askWithStatus(replyTo => Command.DeliverSignal(signalDef, req, replyTo)))
  }

  override def wakeup(): LazyFuture[Unit] = {
    given Timeout = processingTimeout
    LazyFuture.fromFuture(actorRef.askWithStatus(replyTo => Command.Wakeup(replyTo)))
  }

  override def getProgress: LazyFuture[WIOExecutionProgress[WCState[Ctx]]] = {
    given Timeout = queryTimeout
    LazyFuture.fromFuture(actorRef.ask(replyTo => Command.GetProgress(replyTo)))
  }

  override def getExpectedSignals: LazyFuture[List[SignalDef[?, ?]]] = {
    given Timeout = queryTimeout
    LazyFuture.fromFuture(actorRef.ask(replyTo => Command.GetExpectedSignals(replyTo)))
  }
}
