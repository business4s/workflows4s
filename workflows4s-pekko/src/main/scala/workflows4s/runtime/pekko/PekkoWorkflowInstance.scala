package workflows4s.runtime.pekko

import cats.effect.IO
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.{ActorSystem, RecipientRef}
import org.apache.pekko.util.Timeout
import workflows4s.runtime.pekko.WorkflowBehavior.Command
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
    extends WorkflowInstance[IO, WCState[Ctx]] {

  override def queryState(): IO[WCState[Ctx]] = {
    given Timeout = queryTimeout
    IO.fromFuture(IO(actorRef.ask(replyTo => Command.QueryState(replyTo))))
  }

  override def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): IO[Either[WorkflowInstance.UnexpectedSignal, Resp]] = {
    given Timeout = processingTimeout
    IO.fromFuture(IO(actorRef.askWithStatus(replyTo => Command.DeliverSignal(signalDef, req, replyTo))))
  }

  override def wakeup(): IO[Unit] = {
    given Timeout = processingTimeout
    IO.fromFuture(IO(actorRef.askWithStatus(replyTo => Command.Wakeup(replyTo))))
  }

  override def getProgress: IO[WIOExecutionProgress[WCState[Ctx]]] = {
    given Timeout = queryTimeout
    IO.fromFuture(IO(actorRef.ask(replyTo => Command.GetProgress(replyTo))))
  }

  override def getExpectedSignals: IO[List[SignalDef[?, ?]]] = {
    given Timeout = queryTimeout
    IO.fromFuture(IO(actorRef.ask(replyTo => Command.GetExpectedSignals(replyTo))))
  }
}
