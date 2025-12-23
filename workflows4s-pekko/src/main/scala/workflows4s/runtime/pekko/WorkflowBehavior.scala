package workflows4s.runtime.pekko

import cats.effect.IO
import cats.implicits.catsSyntaxOptionId
import com.typesafe.scalalogging.StrictLogging
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.pattern.StatusReply
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import workflows4s.cats.CatsEffect.given
import workflows4s.runtime.WorkflowInstance.UnexpectedSignal
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine.PostExecCommand
import workflows4s.wio.*
import workflows4s.wio.internal.WakeupResult.ProcessingResult
import workflows4s.wio.model.WIOExecutionProgress

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.util.chaining.scalaUtilChainingOps
import scala.util.{Failure, Success}

object WorkflowBehavior {

  opaque type StateLockId <: String = String
  object StateLockId {
    def random(): StateLockId = UUID.randomUUID().toString
  }

  def apply[Ctx <: WorkflowContext](
      instanceId: WorkflowInstanceId,
      id: PersistenceId,
      workflow: WIO.Initial[IO, Ctx],
      initialState: WCState[Ctx],
      engine: WorkflowInstanceEngine[IO],
  ): Behavior[Command[Ctx]] =
    new WorkflowBehavior(instanceId, id, workflow, initialState, engine).behavior

  object LockExpired

  sealed trait Command[Ctx <: WorkflowContext]
  object Command {
    case class QueryState[Ctx <: WorkflowContext](replyTo: ActorRef[WCState[Ctx]])                        extends Command[Ctx]
    case class DeliverSignal[Ctx <: WorkflowContext, Req, Resp](
        signalDef: SignalDef[Req, Resp],
        req: Req,
        replyTo: ActorRef[StatusReply[Either[UnexpectedSignal, Resp]]],
    ) extends Command[Ctx]
    case class Wakeup[Ctx <: WorkflowContext](replyTo: ActorRef[StatusReply[Unit]])                       extends Command[Ctx]
    case class GetProgress[Ctx <: WorkflowContext](replyTo: ActorRef[WIOExecutionProgress[WCState[Ctx]]]) extends Command[Ctx]
    case class GetExpectedSignals[Ctx <: WorkflowContext](replyTo: ActorRef[List[SignalDef[?, ?]]])       extends Command[Ctx]

    case class Reply[Ctx <: WorkflowContext, T](replyTo: ActorRef[T], msg: T, unlock: Boolean) extends Command[Ctx]
    case class Persist[Ctx <: WorkflowContext](event: WCEvent[Ctx], reply: Reply[Ctx, ?])      extends Command[Ctx]
    case class NoOp[Ctx <: WorkflowContext]()                                                  extends Command[Ctx]
    case class FollowupWakeup[Ctx <: WorkflowContext](replyTo: ActorRef[StatusReply[Unit]])    extends Command[Ctx]
  }

  final case class State[Ctx <: WorkflowContext](workflow: ActiveWorkflow[IO, Ctx])

  sealed trait SignalResponse[+Resp]
  object SignalResponse {
    case class Success[+Resp](response: Resp) extends SignalResponse[Resp]
    case object Unexpected                    extends SignalResponse[Nothing]
    case class Failed(error: Throwable)       extends SignalResponse[Nothing]
  }
}

private class WorkflowBehavior[Ctx <: WorkflowContext](
    instanceId: WorkflowInstanceId,
    id: PersistenceId,
    workflow: WIO.Initial[IO, Ctx],
    initialState: WCState[Ctx],
    engine: WorkflowInstanceEngine[IO],
) extends StrictLogging {
  import WorkflowBehavior.*

  private type Event = WCEvent[Ctx]
  private type Cmd   = Command[Ctx]
  private type St    = State[Ctx]

  enum ProcessingState {
    case Locked(id: StateLockId)
    case Free
  }

  val behavior: Behavior[Cmd] = Behaviors.setup { actorContext =>
    // doesn't have to be atomic but its what we have in stdlib
    val processingState: AtomicReference[ProcessingState] = new AtomicReference(ProcessingState.Free)
    val initialWf: ActiveWorkflow[IO, Ctx]                = ActiveWorkflow(instanceId, workflow, initialState)
    EventSourcedBehavior[Cmd, Event, St](
      persistenceId = id,
      emptyState = State(initialWf),
      commandHandler = (state, cmd) => {
        cmd match {
          case Command.QueryState(replyTo)         => Effect.reply(replyTo)(state.workflow.liveState)
          case x: Command.DeliverSignal[Ctx, ?, ?] => handleDeliverSignal(x, processingState, actorContext)
          case x: Command.Wakeup[Ctx]              => handleWakeup(x.replyTo, processingState, actorContext, honorLock = true)
          case x: Command.FollowupWakeup[Ctx]      => handleWakeup(x.replyTo, processingState, actorContext, honorLock = false)
          case x: Command.GetProgress[Ctx]         => Effect.reply(x.replyTo)(state.workflow.progress)
          case x: Command.GetExpectedSignals[Ctx]  => Effect.reply(x.replyTo)(state.workflow.expectedSignals)
          case x: Command.Reply[Ctx, ?]            =>
            Effect
              .none[Event, St]
              .thenRun(_ => if x.unlock then processingState.set(ProcessingState.Free))
              .thenReply(x.replyTo)(_ => x.msg)
              .thenUnstashAll()
          case x: Command.Persist[Ctx]             =>
            import cats.effect.unsafe.implicits.global
            Effect
              .persist[Event, St](x.event)
              .thenRun(newState => {
                actorContext.pipeToSelf(engine.onStateChange(state.workflow, newState.workflow).unsafeToFuture())({
                  case Failure(exception) =>
                    logger.error("Error when running onStateChange hook", exception)
                    x.reply
                  case Success(cmds)      =>
                    val responder = actorContext.spawnAnonymous(replyOnWakeupComplete(x.reply))
                    val other     = cmds.toList.map({ case PostExecCommand.WakeUp =>
                      Command.FollowupWakeup[Ctx](responder)
                    })
                    if other.size == 1 then other.head
                    else if other.isEmpty then x.reply
                    else ??? // Should never happen
                })
              })
          case _: Command.NoOp[Ctx]                => Effect.none
        }
      },
      eventHandler = handleEvent,
    )
  }

  private def handleEvent(state: St, event: Event): State[Ctx] = {
    import cats.effect.unsafe.implicits.global
    engine
      .processEvent(state.workflow, event)
      .unsafeRunSync()
      .pipe(State.apply)
  }

  private def handleDeliverSignal[Req, Resp](
      cmd: Command.DeliverSignal[Ctx, Req, Resp],
      processingState: AtomicReference[ProcessingState],
      actorContext: ActorContext[Command[Ctx]],
  ): Effect[Event, St] = {
    changeStateAsync[(WCEvent[Ctx], Resp), Either[UnexpectedSignal, Resp]](
      processingState,
      actorContext,
      state => engine.handleSignal(state.workflow, cmd.signalDef, cmd.req).map(_.toRaw),
      cmd.replyTo,
      {
        case Some(value) => Right(value._2)
        case None        => Left(UnexpectedSignal(cmd.signalDef))
      },
      x => x._1.some,
    )
  }
  private def handleWakeup(
      replyTo: ActorRef[StatusReply[Unit]],
      processingState: AtomicReference[ProcessingState],
      actorContext: ActorContext[Command[Ctx]],
      honorLock: Boolean,
  ): Effect[Event, St] = {
    changeStateAsync[ProcessingResult[WCEvent[Ctx]], Unit](
      processingState,
      actorContext,
      state => engine.triggerWakeup(state.workflow).map(_.toRaw),
      replyTo,
      _ => (),
      {
        case ProcessingResult.Proceeded(event) => Some(event)
        case _                                 => None
      },
      honorLock = honorLock,
    )
  }

  private def changeStateAsync[T, Resp](
      processingState: AtomicReference[ProcessingState],
      actorContext: ActorContext[Command[Ctx]],
      logic: St => IO[Option[IO[T]]],
      replyTo: ActorRef[StatusReply[Resp]],
      formResponse: Option[T] => Resp,
      getEvent: T => Option[Event],
      honorLock: Boolean = true,
  ): Effect[Event, St] = {
    processingState.get() match {
      case ProcessingState.Locked(_) if honorLock           => Effect.stash()
      case ProcessingState.Free | ProcessingState.Locked(_) =>
        import cats.effect.unsafe.implicits.global
        Effect
          .none[Event, St]
          .thenRun(_ => processingState.set(ProcessingState.Locked(StateLockId.random())))
          .thenRun(state =>
            actorContext.pipeToSelf(logic(state).unsafeToFuture())({
              case Failure(exception)  => Command.Reply(replyTo, StatusReply.error(exception), unlock = true)
              case Success(eventIoOpt) =>
                eventIoOpt match {
                  case Some(eventIO) =>
                    actorContext.pipeToSelf(eventIO.unsafeToFuture())({
                      case Failure(exception) => Command.Reply(replyTo, StatusReply.error(exception), unlock = true)
                      case Success(output)    =>
                        val replyCmd = Command.Reply[Ctx, StatusReply[Resp]](replyTo, StatusReply.success(formResponse(Some(output))), unlock = true)
                        getEvent(output) match {
                          case Some(event) => Command.Persist(event, replyCmd)
                          case None        => replyCmd
                        }
                    })
                    Command.NoOp()
                  case None          => Command.Reply(replyTo, StatusReply.success(formResponse(None)), unlock = true)
                }
            }),
          )
    }

  }

  private def replyOnWakeupComplete(reply: Command.Reply[Ctx, ?]): Behaviors.Receive[StatusReply[Unit]] =
    Behaviors.receiveMessage[StatusReply[Unit]] {
      case StatusReply.Success(_)   =>
        reply.replyTo ! reply.msg
        Behaviors.stopped
      case StatusReply.Error(error) =>
        logger.error("Received error from post-exec wakeup: {}", error)
        reply.replyTo ! reply.msg
        Behaviors.stopped
    }

}
