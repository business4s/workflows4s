package workflows4s.runtime.pekko

import cats.implicits.catsSyntaxEitherId
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.{ActorSystem, RecipientRef}
import org.apache.pekko.util.Timeout
import workflows4s.runtime.WorkflowInstance
import workflows4s.runtime.pekko.WorkflowBehavior.SignalResponse
import workflows4s.wio.model.WIOExecutionProgress
import workflows4s.wio.{SignalDef, WCState, WorkflowContext}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

class PekkoWorkflowInstance[Ctx <: WorkflowContext](
    actorRef: RecipientRef[WorkflowBehavior.Command[Ctx]],
    stateQueryTimeout: Timeout = Timeout(100.millis),
    signalTimeout: Timeout = Timeout(5.second),
)(using system: ActorSystem[?])
    extends WorkflowInstance[Future, WCState[Ctx]] {

  override def queryState(): Future[WCState[Ctx]] = {
    given Timeout = stateQueryTimeout
    actorRef.ask[WCState[Ctx]](replyTo => WorkflowBehavior.Command.QueryState(replyTo))
  }

  override def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): Future[Either[WorkflowInstance.UnexpectedSignal, Resp]] = {
    given Timeout          = signalTimeout
    given ExecutionContext = system.executionContext
    actorRef
      .ask[SignalResponse[Resp]](replyTo => WorkflowBehavior.Command.DeliverSignal(signalDef, req, replyTo))
      .map({
        case SignalResponse.Success(response) => response.asRight
        case SignalResponse.Unexpected        => WorkflowInstance.UnexpectedSignal(signalDef).asLeft
      })
  }

  override def wakeup(): Future[Unit] = {
    given Timeout = signalTimeout
    actorRef.ask[Unit](replyTo => WorkflowBehavior.Command.Wakeup(replyTo))
  }

  override def getProgress: Future[WIOExecutionProgress[WCState[Ctx]]] = {
    given Timeout = stateQueryTimeout
    actorRef.ask(ref => WorkflowBehavior.Command.GetProgress(ref))
  }
}
