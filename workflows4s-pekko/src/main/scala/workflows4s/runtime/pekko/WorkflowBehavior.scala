package workflows4s.runtime.pekko

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import cats.implicits.catsSyntaxOptionId
import com.typesafe.scalalogging.StrictLogging
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import org.apache.pekko.persistence.typed.{PersistenceId, RecoveryCompleted}
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.wio.*
import workflows4s.wio.model.WIOExecutionProgress

import java.time.{Clock, Instant}
import scala.annotation.nowarn

object WorkflowBehavior {

  def apply[Ctx <: WorkflowContext](
      id: PersistenceId,
      workflow: WIO.Initial[Ctx],
      initialState: WCState[Ctx],
      clock: Clock,
      knockerUpper: KnockerUpper.Agent.Curried,
  )(using ioRuntime: IORuntime): Behavior[Command[Ctx]] =
    new WorkflowBehavior(id, workflow, initialState, clock, knockerUpper).behavior

  sealed trait Command[Ctx <: WorkflowContext]
  object Command {
    case class DeliverSignal[Req, Resp, Ctx <: WorkflowContext](
        signalDef: SignalDef[Req, Resp],
        request: Req,
        replyTo: ActorRef[SignalResponse[Resp]],
    ) extends Command[Ctx]
    case class QueryState[Ctx <: WorkflowContext](replyTo: ActorRef[WCState[Ctx]])                        extends Command[Ctx]
    case class Wakeup[Ctx <: WorkflowContext](replyTo: ActorRef[Unit])                                    extends Command[Ctx]
    case class GetProgress[Ctx <: WorkflowContext](replyTo: ActorRef[WIOExecutionProgress[WCState[Ctx]]]) extends Command[Ctx]

    private[WorkflowBehavior] case class PersistEvent[Ctx <: WorkflowContext, T](event: WCEvent[Ctx], confirm: Option[(ActorRef[T], T)])
        extends Command[Ctx]
    private[WorkflowBehavior] case class UpdateWakeup(wakeup: Option[Instant]) extends Command[?]
  }

  final private case class State[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx], awaitingCommandResult: Boolean)

  case object CommandAccepted

  sealed trait SignalResponse[+Resp]
  object SignalResponse {
    case class Success[+Resp](response: Resp) extends SignalResponse[Resp]
    case object Unexpected                    extends SignalResponse[Nothing]
    case class Failed(error: Throwable)       extends SignalResponse[Nothing]
  }
}

private class WorkflowBehavior[Ctx <: WorkflowContext](
    id: PersistenceId,
    workflow: WIO.Initial[Ctx],
    initialState: WCState[Ctx],
    clock: Clock,
    knockerUpper: KnockerUpper.Agent.Curried,
)(using ioRuntime: IORuntime)
    extends StrictLogging {
  import WorkflowBehavior.*

  private type Event = WCEvent[Ctx] | CommandAccepted.type
  private type Cmd   = Command[Ctx]
  private type St    = State[Ctx]

  val behavior: Behavior[Cmd] = Behaviors.setup { context =>
    val initialWf: ActiveWorkflow[Ctx] = ActiveWorkflow(workflow.provideInput(()), initialState)
    EventSourcedBehavior[Cmd, Event, St](
      persistenceId = id,
      emptyState = State(initialWf, awaitingCommandResult = false),
      commandHandler = (state, cmd) =>
        cmd match {
          case cmd: Command.DeliverSignal[?, resp, Ctx] => handleSignalDelivery(cmd, state, context)
          case Command.QueryState(replyTo)              => Effect.none.thenRun(state => replyTo ! state.workflow.liveState(clock.instant()))
          case cmd: Command.Wakeup[Ctx]                 => handleWakeup(cmd, state, context)
          case cmd: Command.PersistEvent[Ctx, ?]        => handlePersistEvent(cmd, state)
          case cmd: Command.UpdateWakeup                => handleUpdateWakeup(cmd)
          case cmd: Command.GetProgress[Ctx]            => Effect.reply(cmd.replyTo)(state.workflow.wio.toProgress)

        },
      eventHandler = handleEvent,
    ).receiveSignal { case (state, RecoveryCompleted) =>
      logger.debug("Workflow recovered, waking up.")
      context.self ! Command.Wakeup(context.spawnAnonymous(Behaviors.ignore))
    }
  }

  private def handleSignalDelivery[Req, Resp](
      cmd: Command.DeliverSignal[Req, Resp, Ctx],
      state: St,
      context: ActorContext[Cmd],
  ): Effect[Event, St] = {
    if (state.awaitingCommandResult) Effect.stash()
    else {
      state.workflow.handleSignal(cmd.signalDef)(cmd.request, clock.instant()) match {
        case Some(resultIO: IO[(WCEvent[Ctx], resp)]) =>
          Effect
            .persist(CommandAccepted)
            .thenRun((_: St) => {
              // has to be spawned from actor thread
              val ignore = context.spawnAnonymous(Behaviors.ignore)
              val _      = resultIO
                .map((event, resp) => {
                  context.self ! Command.PersistEvent(event, (cmd.replyTo, SignalResponse.Success(resp)).some)
                  context.self ! Command.Wakeup(ignore)
                })
                .handleError(err => cmd.replyTo ! SignalResponse.Failed(err))
                .unsafeToFuture()
            })
        case None                                     =>
          Effect.none
            .thenRun(_ => cmd.replyTo ! SignalResponse.Unexpected)
      }
    }
  }

  private def handleWakeup(cmd: Command.Wakeup[Ctx], state: St, context: ActorContext[Cmd]): Effect[Event, St] = {
    logger.debug(s"Waking up.")
    if (state.awaitingCommandResult) {
      logger.debug(s"Another command processing. Stashing")
      Effect.stash()
    } else {
      state.workflow.proceed(clock.instant()) match {
        case Some(eventIO) =>
          logger.debug("Got new state during wakeup. Evaluating")
          Effect
            .persist(CommandAccepted)
            .thenRun((_: St) => {
              val _ = eventIO
                .map(event => {
                  logger.debug(s"New event evaluated to ${event}. Persisting. ")
                  context.self ! Command.PersistEvent(event, None)
                  context.self ! Command.Wakeup(cmd.replyTo)
                })
                .handleError(err => logger.error("Failed to execute workflow", err))
                .unsafeToFuture()
            })
        case None          =>
          logger.debug("No new state during wakeup.")
          Effect.none.thenRun(_ => cmd.replyTo ! ())
      }
    }
  }

  // it's safe, compiler cant get patmatch exhaustivity
  @nowarn("msg=he type test for workflows4s.wio.WorkflowContext.Event")
  private def handleEvent(state: St, event: Event): State[Ctx] = {
    event match {
      case CommandAccepted       => state.copy(awaitingCommandResult = true)
      // compiler cant see that patmatch is exhaustive
      case wfEvent: WCEvent[Ctx] =>
        state.workflow.handleEvent(wfEvent, clock.instant()) match {
          case Some(newWf) => State(newWf, awaitingCommandResult = false)
          case None        =>
            logger.warn(s"Unhandled and ignored event for workflow $id. Event: $event")
            state
        }
    }
  }

  private def handlePersistEvent[Response](cmd: Command.PersistEvent[Ctx, Response], state: St) = {
    logger.debug(s"Persisting event ${cmd.event}")
    Effect
      .persist(cmd.event)
      .thenRun((newState: St) => {
        cmd.confirm match {
          case Some((replyTo, response)) =>
            logger.debug(s"Replying to ${replyTo} with ${response} after persisting ${cmd.event}")
            replyTo ! response
          case None                      => ()
        }
        if (state.workflow.wakeupAt != newState.workflow.wakeupAt) {
          val _ = knockerUpper.updateWakeup((), newState.workflow.wakeupAt).unsafeToFuture()
        }
      })
      .thenUnstashAll()
  }

  private def handleUpdateWakeup(cmd: Command.UpdateWakeup): Effect[Event, St] = {
    logger.debug(s"Updating wakeup to ${cmd.wakeup}")
    val _ = knockerUpper
      .updateWakeup((), cmd.wakeup)
      .handleError(err => {
        logger.error(s"Error when updating wakeup for workflow $id", err)
      })
      .unsafeToFuture()
    Effect.none
  }

}
