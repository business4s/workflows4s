package workflows4s.runtime.pekko

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import workflow4s.wio.*

import java.time.Instant

object WorkflowBehavior {

  def apply[Ctx <: WorkflowContext, In <: WCState[Ctx]](id: PersistenceId, workflow: WIO.Initial[Ctx, In], initialState: In)(implicit
      ioRuntime: IORuntime,
  ): Behavior[Command[Ctx]] =
    new WorkflowBehavior(id, workflow, initialState).behavior

  sealed trait Command[Ctx <: WorkflowContext]
  object Command {
    case class DeliverSignal[Req, Resp, Ctx <: WorkflowContext](
        signalDef: SignalDef[Req, Resp],
        request: Req,
        replyTo: ActorRef[SignalResponse[Resp]],
    ) extends Command[Ctx]
    case class QueryState[Ctx <: WorkflowContext](replyTo: ActorRef[WCState[Ctx]]) extends Command[Ctx]
    case class Wakeup[Ctx <: WorkflowContext]()                                    extends Command[Ctx]

    private[WorkflowBehavior] case class PersistEvent[Ctx <: WorkflowContext](event: WCEvent[Ctx]) extends Command[Ctx]
  }

  final private case class State[Ctx <: WorkflowContext](workflow: ActiveWorkflow.ForCtx[Ctx], awaitingCommandResult: Boolean)

  sealed private trait EventEnvelope[+Event]
  private object EventEnvelope {
    case class WorkflowEvent[+Event](event: Event) extends EventEnvelope[Event]
    case object CommandAccepted                    extends EventEnvelope[Nothing]
  }

  sealed trait SignalResponse[+Resp]
  object SignalResponse {
    case class Success[+Resp](response: Resp) extends SignalResponse[Resp]
    case object Unexpected                    extends SignalResponse[Nothing]
//    case object Failed extends SignalResponse[Nothing]
  }
}

private class WorkflowBehavior[Ctx <: WorkflowContext, In <: WCState[Ctx]](id: PersistenceId, workflow: WIO.Initial[Ctx, In], initialState: In)(
    implicit ioRuntime: IORuntime,
) {
  import WorkflowBehavior.*

  private type Event = EventEnvelope[WCEvent[Ctx]]
  private type Cmd   = Command[Ctx]
  private type St    = State[Ctx]

  val behavior: Behavior[Cmd] = Behaviors.setup { context =>
    Behaviors.withTimers { timers =>
      val knockerUpper   = PekkoKnockerUpper(timers)
      val activeWorkflow: ActiveWorkflow.ForCtx[Ctx] = ActiveWorkflow(workflow, initialState)(Interpreter(knockerUpper))
      EventSourcedBehavior[Cmd, Event, St](
        persistenceId = id,
        emptyState = State(activeWorkflow, awaitingCommandResult = false),
        commandHandler = (state, cmd) =>
          cmd match {
            case cmd: Command.DeliverSignal[?, resp, Ctx] => handleSignalDelivery(cmd, state, context)
            case Command.QueryState(replyTo)              => Effect.none.thenRun(state => replyTo ! state.workflow.state)
            case Command.Wakeup()                         => handleWakeup(state, context)
            case Command.PersistEvent(event)              => Effect.persist(EventEnvelope.WorkflowEvent(event)).thenUnstashAll()
          },
        eventHandler = handleEvent,
      )
    }
  }

  private def handleSignalDelivery[Req, Resp](
      cmd: Command.DeliverSignal[Req, Resp, Ctx],
      state: St,
      context: ActorContext[Cmd],
  ): Effect[Event, St] = {
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

  private def handleWakeup(state: St, context: ActorContext[Cmd]): Effect[Event, St] = {
    if (state.awaitingCommandResult) Effect.stash()
    else {
      state.workflow.proceed(Instant.now()) match {
        case Some(eventIO) =>
          Effect
            .persist(EventEnvelope.CommandAccepted)
            .thenRun((_: St) => {
              eventIO
                .map(event => context.self ! Command.PersistEvent(event))
                .unsafeToFuture()
              // TODO error handling
              ()
            })
        case None          => Effect.none
      }
    }
  }

  private def handleEvent(state: St, event: EventEnvelope[WCEvent[Ctx]]): State[Ctx] = {
    event match {
      case EventEnvelope.WorkflowEvent(event) =>
        state.workflow.handleEvent(event, Instant.now()) match {
          case Some(value) => State(value, awaitingCommandResult = false)
          // TODO think about good behaviour here. Ignore or fail?
          case None        => ???
        }
      case EventEnvelope.CommandAccepted      => state.copy(awaitingCommandResult = true)
    }
  }

}
