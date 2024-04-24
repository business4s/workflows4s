package workflows4s.runtime.pekko

import cats.effect.IO
import org.apache.pekko
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EffectBuilder}
import workflow4s.wio.*

import java.time.Instant
import scala.util.chaining.scalaUtilChainingOps

object WorkflowBehaviour {

  sealed trait Command[Ctx <: WorkflowContext]
  object Command {
    case class DeliverSignal[Req, Resp, Ctx <: WorkflowContext](
        signalDef: SignalDef[Req, Resp],
        request: Req,
        replyTo: ActorRef[SignalResponse[Resp]],
    ) extends Command[Ctx]
    case class QueryState[Ctx <: WorkflowContext](replyTo: ActorRef[WCState[Ctx]]) extends Command[Ctx]
    case class Wakeup[Ctx <: WorkflowContext]()                                    extends Command[Ctx]

    private[WorkflowBehaviour] case class PersistEvent[Ctx <: WorkflowContext](event: WCEvent[Ctx]) extends Command[Ctx]
  }

  final case class State[Ctx <: WorkflowContext](workflow: ActiveWorkflow.ForCtx[Ctx], awaitingCommandResult: Boolean)

  sealed trait EventEnvelope[+Event]
  object EventEnvelope {
    case class WorkflowEvent[+Event](event: Event) extends EventEnvelope[Event]
    case object CommandAccepted                    extends EventEnvelope[Nothing]
  }

  sealed trait SignalResponse[+Resp]
  object SignalResponse {
    case class Success[+Resp](response: Resp) extends SignalResponse[Resp]
    case object Unexpected                    extends SignalResponse[Nothing]
//    case object Failed extends SignalResponse[Nothing]
  }

  def apply[Ctx <: WorkflowContext](workflow: ActiveWorkflow.ForCtx[Ctx]): Behavior[Command[Ctx]] =
    Behaviors.setup { context =>
      EventSourcedBehavior[Command[Ctx], EventEnvelope[WCEvent[Ctx]], State[Ctx]](
        persistenceId = PersistenceId.ofUniqueId("abc"),
        emptyState = State(workflow, awaitingCommandResult = false),
        commandHandler = (state, cmd) =>
          cmd match {
            case x: Command.DeliverSignal[?, ?, Ctx] =>
              if (state.awaitingCommandResult) Effect.stash()
              else {
                state.workflow.handleSignal(x.signalDef)(x.request, Instant.now()) match {
                  case Some(resultIO) =>
                    Effect
                      .persist(EventEnvelope.CommandAccepted)
                      .pipe(handleSignalResult(_, resultIO, x.replyTo, context.self))
                  case None           =>
                    Effect.none
                      .thenRun(_ => x.replyTo ! SignalResponse.Unexpected)
                }
              }
            case Command.QueryState(replyTo)    => Effect.none.thenRun(state => replyTo ! state.workflow.state)
            case Command.Wakeup()               =>
              if (state.awaitingCommandResult) Effect.stash()
              else {
                state.workflow.runIO(Instant.now()) match {
                  case Some(eventIO) =>
                    Effect
                      .persist(EventEnvelope.CommandAccepted)
                      .pipe(handleWakeupResult(_, eventIO, context.self))
                  case None          => Effect.none
                }
              }
            case Command.PersistEvent(event)    =>
              Effect.persist(event).thenUnstashAll()
          },
        eventHandler = (state, evt) =>
          evt match {
            case EventEnvelope.WorkflowEvent(event) =>
              state.workflow.handleEvent(event, Instant.now()) match {
                case Some(value) => State(value, awaitingCommandResult = false)
                case None        =>
                  // TODO think about good behaviour here. Ignore or fail?
                  ???
              }
            case EventEnvelope.CommandAccepted      =>
              state.copy(awaitingCommandResult = true)
          },
      )
    }

  def handleSignalResult[Ctx <: WorkflowContext, Resp](
      effect: EffectBuilder[EventEnvelope[WCEvent[Ctx]], State[Ctx]],
      result: IO[(WCEvent[Ctx], Resp)],
      replyTo: ActorRef[SignalResponse[Resp]],
      self: ActorRef[Command[Ctx]],
  ): EffectBuilder[EventEnvelope[WCEvent[Ctx]], State[Ctx]] = {
    effect.thenRun(_ => {
      result
        .unsafeToFuture()
        .map((event, resp) => {
          replyTo ! SignalResponse.Success(resp)
          self ! Command.PersistEvent(event)
        })
      // TODO error handling
      ()
    })
  }

  def handleWakeupResult[Ctx <: WorkflowContext](
      effect: EffectBuilder[EventEnvelope[WCEvent[Ctx]], State[Ctx]],
      result: IO[WCEvent[Ctx]],
      self: ActorRef[Command[Ctx]],
  ): EffectBuilder[EventEnvelope[WCEvent[Ctx]], State[Ctx]] = {
    effect.thenRun(_ => {
      result
        .unsafeToFuture()
        .map((event, resp) => {
          self ! Command.PersistEvent(event)
        })
      // TODO error handling
      ()
    })
  }

}
