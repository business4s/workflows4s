package workflows4s.runtime.pekko

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EffectBuilder, EventSourcedBehavior}
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

  def apply[Ctx <: WorkflowContext](workflow: ActiveWorkflow.ForCtx[Ctx])(implicit ioRuntime: IORuntime): Behavior[Command[Ctx]] =
    new WorkflowBehaviour(workflow).bahviour
}

private class WorkflowBehaviour[Ctx <: WorkflowContext](initialState: ActiveWorkflow.ForCtx[Ctx])(implicit ioRuntime: IORuntime) {
  import WorkflowBehaviour.*

  type Event = EventEnvelope[WCEvent[Ctx]]
  type Cmd   = Command[Ctx]
  type St    = State[Ctx]

  val bahviour: Behavior[Cmd] = Behaviors.setup { context =>
    EventSourcedBehavior[Cmd, Event, St](
      persistenceId = PersistenceId.ofUniqueId("abc"),
      emptyState = State(initialState, awaitingCommandResult = false),
      commandHandler = (state, cmd) =>
        cmd match {
          case cmd: Command.DeliverSignal[?, resp, Ctx] => handleSignalDelivery(cmd, state, context)

          case Command.QueryState(replyTo) => Effect.none.thenRun(state => replyTo ! state.workflow.state)
          case Command.Wakeup()            =>
            if (state.awaitingCommandResult) Effect.stash()
            else {
              state.workflow.proceed(Instant.now()) match {
                case Some(eventIO) =>
                  Effect
                    .persist(EventEnvelope.CommandAccepted)
                    .pipe(handleWakeupResult(_, eventIO, context.self))
                case None          => Effect.none
              }
            }
          case Command.PersistEvent(event) =>
            Effect.persist(EventEnvelope.WorkflowEvent(event)).thenUnstashAll()
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

  def handleSignalDelivery[Req, Resp](cmd: Command.DeliverSignal[Req, Resp, Ctx], state: St, context: ActorContext[Cmd]): Effect[Event, St] = {
    if (state.awaitingCommandResult) Effect.stash()
    else {
      state.workflow.handleSignal(cmd.signalDef)(cmd.request, Instant.now()) match {
        case Some(resultIO: IO[(WCEvent[Ctx], resp)]) =>
          Effect
            .persist(EventEnvelope.CommandAccepted)
            .thenRun((_: St) => {
              resultIO
                .map((event, resp) => {
                  cmd.replyTo ! SignalResponse.Success(resp)
                  context.self ! Command.PersistEvent(event)
                })
                .unsafeToFuture()
              // TODO error handling
              ()
            })
        case None                                     =>
          Effect.none
            .thenRun(_ => cmd.replyTo ! SignalResponse.Unexpected)
      }
    }
  }

  def handleWakeupResult(
      effect: EffectBuilder[Event, St],
      result: IO[WCEvent[Ctx]],
      self: ActorRef[Cmd],
  ): EffectBuilder[Event, St] = {
    effect.thenRun(_ => {
      result
        .map(event => self ! Command.PersistEvent(event))
        .unsafeToFuture()
      // TODO error handling
      ()
    })
  }

}
