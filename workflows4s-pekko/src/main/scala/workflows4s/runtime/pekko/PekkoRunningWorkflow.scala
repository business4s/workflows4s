package workflows4s.runtime.pekko

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

import cats.implicits.catsSyntaxEitherId
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior, RecipientRef}
import org.apache.pekko.util.Timeout
import workflow4s.runtime.RunningWorkflow
import workflow4s.wio.{SignalDef, WCState, WorkflowContext}
import workflows4s.runtime.pekko.WorkflowBehavior.SignalResponse

class PekkoRunningWorkflow[Ctx <: WorkflowContext](
    actorRef: RecipientRef[WorkflowBehavior.Command[Ctx]],
    stateQueryTimeout: Timeout = Timeout(100.millis),
    signalTimeout: Timeout = Timeout(5.second),
)(using system: ActorSystem[?])
    extends RunningWorkflow[Future, WCState[Ctx]] {

  override def queryState(): Future[WCState[Ctx]] = {
    given Timeout = stateQueryTimeout
    actorRef.ask[WCState[Ctx]](replyTo => WorkflowBehavior.Command.QueryState(replyTo))
  }

  override def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): Future[Either[RunningWorkflow.UnexpectedSignal, Resp]] = {
    given Timeout          = signalTimeout
    given ExecutionContext = system.executionContext
    actorRef
      .ask[SignalResponse[Resp]](replyTo => WorkflowBehavior.Command.DeliverSignal(signalDef, req, replyTo))
      .map({
        case SignalResponse.Success(response) => response.asRight
        case SignalResponse.Unexpected        => RunningWorkflow.UnexpectedSignal(signalDef).asLeft
      })
  }

  override def wakeup(): Future[Unit] = {
    given Timeout = signalTimeout
    actorRef.ask[Unit](replyTo => WorkflowBehavior.Command.Wakeup(replyTo))
  }
}
