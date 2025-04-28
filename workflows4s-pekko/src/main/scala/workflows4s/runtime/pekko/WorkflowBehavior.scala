package workflows4s.runtime.pekko

import cats.effect.unsafe.IORuntime
import com.typesafe.scalalogging.StrictLogging
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import workflows4s.wio.*

import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

object WorkflowBehavior {

  opaque type StateLockId <: String = String
  object StateLockId {
    def random(): StateLockId = UUID.randomUUID().toString
  }

  def apply[Ctx <: WorkflowContext](
      id: PersistenceId,
      workflow: WIO.Initial[Ctx],
      initialState: WCState[Ctx],
      clock: Clock,
  )(using ioRuntime: IORuntime): Behavior[Command[Ctx]] =
    new WorkflowBehavior(id, workflow, initialState, clock).behavior

  object LockExpired

  sealed trait Command[Ctx <: WorkflowContext]
  object Command {
    case class QueryState[Ctx <: WorkflowContext](replyTo: ActorRef[ActiveWorkflow[Ctx]])                 extends Command[Ctx]
    // TODO should we communicate lock duration?
    case class LockState[Ctx <: WorkflowContext](id: StateLockId, replyTo: ActorRef[ActiveWorkflow[Ctx]]) extends Command[Ctx]
    case class UpdateState[Ctx <: WorkflowContext](id: StateLockId, replyTo: ActorRef[LockExpired.type | ActiveWorkflow[Ctx]], event: WCEvent[Ctx])
        extends Command[Ctx]
    case class UnlockState[Ctx <: WorkflowContext](id: StateLockId, replyTo: ActorRef[Unit])              extends Command[Ctx]
  }

  final private case class State[Ctx <: WorkflowContext](workflow: ActiveWorkflow[Ctx])

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
) extends StrictLogging {
  import WorkflowBehavior.*

  private type Event = WCEvent[Ctx]
  private type Cmd   = Command[Ctx]
  private type St    = State[Ctx]

  enum ProcessingState {
    case Locked(id: StateLockId)
    case Free
  }

  val behavior: Behavior[Cmd] = Behaviors.setup { context =>
    // doesn't have to be atomic but its what we have in stdlib
    val processingState: AtomicReference[ProcessingState] = new AtomicReference(ProcessingState.Free)
    val initialWf: ActiveWorkflow[Ctx]                    = ActiveWorkflow(workflow.provideInput(()), initialState)
    EventSourcedBehavior[Cmd, Event, St](
      persistenceId = id,
      emptyState = State(initialWf),
      commandHandler = (state, cmd) => {
        logger.trace(s"Received command ${cmd}")
        cmd match {
          case Command.QueryState(replyTo)   => Effect.reply(replyTo)(state.workflow)
          case cmd: Command.LockState[Ctx]   => handleLock(cmd, state, processingState)
          case cmd: Command.UnlockState[Ctx] => handleUnlock(cmd, processingState)
          case cmd: Command.UpdateState[Ctx] => handleUpdateState(cmd, processingState)

        }
      },
      eventHandler = handleEvent,
    )
  }

  private def handleEvent(state: St, event: Event): State[Ctx] = {
    state.workflow.handleEvent(event, clock.instant()) match {
      case Some(newWf) => State(newWf)
      case None        =>
        logger.warn(s"Unhandled and ignored event for workflow $id. Event: $event")
        state
    }
  }

  private def handleLock(cmd: Command.LockState[Ctx], state: St, processingState: AtomicReference[ProcessingState]): Effect[Event, St] = {
    processingState.get() match {
      case ProcessingState.Locked(cmd.id) | ProcessingState.Free =>
        logger.trace(s"Locked state with lock id ${cmd.id}")
        processingState.set(ProcessingState.Locked(cmd.id))
        Effect.reply(cmd.replyTo)(state.workflow)
      case ProcessingState.Locked(id)                            =>
        logger.debug(s"State already locked by id ${id}, request with id ${cmd.id} will be stashed.")
        Effect.stash()
    }
  }
  private def handleUnlock(cmd: Command.UnlockState[Ctx], processingState: AtomicReference[ProcessingState]): Effect[Event, St]        = {
    processingState.get() match {
      case ProcessingState.Locked(cmd.id) => processingState.set(ProcessingState.Free)
      case state                          =>
        logger.warn(s"Tried to unlock with ${cmd.id} but the state is $state")
    }
    // regardless of the state we conclude unlocking as "done" because the lock with that id is no longer kept
    Effect.reply(cmd.replyTo)(())
  }

  private def handleUpdateState(cmd: Command.UpdateState[Ctx], processingState: AtomicReference[ProcessingState]) = {
    processingState.get() match {
      case ProcessingState.Locked(cmd.id)                   =>
        logger.debug(s"Persisting event ${cmd.event}")
        Effect
          .persist(cmd.event)
          .thenRun((newState: St) => {
            processingState.set(ProcessingState.Free)
            cmd.replyTo ! newState.workflow
          })
          .thenUnstashAll()
      case ProcessingState.Locked(_) | ProcessingState.Free =>
        Effect.reply(cmd.replyTo)(LockExpired)
    }
  }

}
