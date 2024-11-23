package workflows4s.runtime.pekko

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import cats.implicits.catsSyntaxOptionId
import com.typesafe.scalalogging.StrictLogging
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.persistence.typed.{PersistenceId, RecoveryCompleted}
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import workflow4s.wio.*

import java.time.{Clock, Instant}
import scala.annotation.nowarn

object WorkflowBehavior {

  def apply[Ctx <: WorkflowContext, In <: WCState[Ctx]](
      id: PersistenceId,
      workflow: WIO.Initial[Ctx, In],
      initialState: In,
      clock: Clock = Clock.systemUTC(),
  )(implicit ioRuntime: IORuntime): Behavior[Command[Ctx]] =
    new WorkflowBehavior(id, workflow, initialState, initialState, clock).behavior

  def withInput[Ctx <: WorkflowContext, In](
      id: PersistenceId,
      workflow: WIO[In, Nothing, WCState[Ctx], Ctx],
      initialState: WCState[Ctx],
      input: In,
      clock: Clock = Clock.systemUTC(),
  )(implicit
      ioRuntime: IORuntime,
  ): Behavior[Command[Ctx]] =
    new WorkflowBehavior(id, workflow, initialState, input, clock).behavior

  sealed trait Command[Ctx <: WorkflowContext]
  object Command {
    case class DeliverSignal[Req, Resp, Ctx <: WorkflowContext](
        signalDef: SignalDef[Req, Resp],
        request: Req,
        replyTo: ActorRef[SignalResponse[Resp]],
    ) extends Command[Ctx]
    case class QueryState[Ctx <: WorkflowContext](replyTo: ActorRef[WCState[Ctx]]) extends Command[Ctx]
    case class Wakeup[Ctx <: WorkflowContext](replyTo: ActorRef[Unit])             extends Command[Ctx]

    private[WorkflowBehavior] case class PersistEvent[Ctx <: WorkflowContext, T](event: WCEvent[Ctx], confirm: Option[(ActorRef[T], T)])
        extends Command[Ctx]
  }

  final private case class State[Ctx <: WorkflowContext](workflow: ActiveWorkflow.ForCtx[Ctx], awaitingCommandResult: Boolean)

  // alternatively we could ask client for embedding of CommandAccepted into `Event`
  // TODO make private
  // maybe we can fix serialization by making it a new class inside behavior, so `Event` is statically captured in that new class?
  // or maybe union type could work?
//  sealed trait EventEnvelope[+Event]
  case object CommandAccepted
//  object EventEnvelope {
//    case class WorkflowEvent[+Event](event: Event) extends EventEnvelope[Event]
//    case object CommandAccepted                    extends EventEnvelope[Nothing]
//  }

  sealed trait SignalResponse[+Resp]
  object SignalResponse {
    case class Success[+Resp](response: Resp) extends SignalResponse[Resp]
    case object Unexpected                    extends SignalResponse[Nothing]
//    case object Failed extends SignalResponse[Nothing]
  }
}

private class WorkflowBehavior[Ctx <: WorkflowContext, In](
    id: PersistenceId,
    workflow: WIO[In, Nothing, WCState[Ctx], Ctx],
    initialState: WCState[Ctx],
    input: In,
    clock: Clock,
)(implicit
    ioRuntime: IORuntime,
) extends StrictLogging {
  import WorkflowBehavior.*

  private type Event = WCEvent[Ctx] | CommandAccepted.type
  private type Cmd   = Command[Ctx]
  private type St    = State[Ctx]

  val behavior: Behavior[Cmd] = Behaviors.setup { context =>
    Behaviors.withTimers { timers =>
      val knockerUpper                               = PekkoKnockerUpper(timers, context)
      val activeWorkflow: ActiveWorkflow.ForCtx[Ctx] = ActiveWorkflow(workflow.provideInput(input), initialState)(Interpreter(knockerUpper))
      EventSourcedBehavior[Cmd, Event, St](
        persistenceId = id,
        emptyState = State(activeWorkflow, awaitingCommandResult = false),
        commandHandler = (state, cmd) =>
          cmd match {
            case cmd: Command.DeliverSignal[?, resp, Ctx] => handleSignalDelivery(cmd, state, context)
            case Command.QueryState(replyTo)              => Effect.none.thenRun(state => replyTo ! state.workflow.state)
            case cmd: Command.Wakeup[Ctx]                 => handleWakeup(cmd, state, context)
            case cmd: Command.PersistEvent[Ctx, ?]        => handlePersistEvent(cmd)

          },
        eventHandler = handleEvent,
      ).receiveSignal { case (state, RecoveryCompleted) =>
        logger.debug("Workflow recovered, waking up.")
        context.self ! Command.Wakeup(context.spawnAnonymous(Behaviors.ignore))
      }
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
              resultIO
                .map((event, resp) => {
                  context.self ! Command.PersistEvent(event, (cmd.replyTo, SignalResponse.Success(resp)).some)
                  val ignore = context.spawnAnonymous(Behaviors.ignore)
                  context.self ! Command.Wakeup(ignore)
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
              val event = eventIO.unsafeRunSync() // TODO shouldnt block actor thread
              logger.debug(s"New event evaluated to ${event}. Persisting. ")
              context.self ! Command.PersistEvent(event, None)
              context.self ! Command.Wakeup(cmd.replyTo)
            })
        case None          =>
          logger.debug("No new state during wakeup.")
          Effect.none.thenRun(_ => cmd.replyTo ! ())
      }
    }
  }

  // it's safe, compiler cant get patmatch exhaustivity
  @nowarn("msg=he type test for workflow4s.wio.WorkflowContext.Event")
  private def handleEvent(state: St, event: Event): State[Ctx] = {
    event match {
      case CommandAccepted       => state.copy(awaitingCommandResult = true)
      // compiler cant see that patmatch is exhaustive
      case wfEvent: WCEvent[Ctx] =>
        state.workflow.handleEvent(wfEvent, clock.instant()) match {
          case Some(newWf) => State(newWf, awaitingCommandResult = false)
          // TODO think about good behaviour here. Ignore or fail?
          case None        => ???
        }
    }
  }

  private def handlePersistEvent[Response](cmd: Command.PersistEvent[Ctx, Response]) = {
    logger.debug(s"Persisting event ${cmd.event}")
    Effect
      .persist(cmd.event)
      .thenRun((_: St) => {
        cmd.confirm match {
          case Some((replyTo, response)) =>
            logger.debug(s"Replying to ${replyTo} with ${response} after persisting ${cmd.event}")
            replyTo ! response
          case None                      => ()
        }
      })
      .thenUnstashAll()
  }

}
