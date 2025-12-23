package workflows4s.doobie

import cats.arrow.FunctionK
import cats.data.Kleisli
import cats.effect.{IO, LiftIO}
import cats.syntax.all.*
import cats.{Monad, ~>}
import com.typesafe.scalalogging.StrictLogging
import doobie.ConnectionIO
import doobie.implicits.*
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.runtime.{WorkflowInstance, WorkflowInstanceId}
import workflows4s.runtime.WorkflowInstance.UnexpectedSignal
import workflows4s.wio.*
import workflows4s.wio.internal.{SignalResult, WakeupResult}
import workflows4s.wio.internal.WakeupResult.ProcessingResult
import workflows4s.wio.model.WIOExecutionProgress

/** Type alias for the Kleisli-based effect used internally. This allows operations to be composed in ConnectionIO while having access to LiftIO for
  * running IO effects.
  */
private[doobie] type DoobieEffect[T] = Kleisli[ConnectionIO, LiftIO[ConnectionIO], T]

/** Database-backed workflow instance using Kleisli[ConnectionIO, LiftIO[ConnectionIO], T] internally. This ensures all operations within a single
  * method call run in the same database transaction.
  */
class DbWorkflowInstance[Ctx <: WorkflowContext](
    val id: WorkflowInstanceId,
    baseWorkflow: ActiveWorkflow[IO, Ctx],
    storage: WorkflowStorage[WCEvent[Ctx]],
    engine: WorkflowInstanceEngine[IO],
) extends WorkflowInstance[DoobieEffect, WCState[Ctx]]
    with StrictLogging {

  private given Monad[DoobieEffect] = Kleisli.catsDataMonadForKleisli

  private val connIOToDoobieEffect: ConnectionIO ~> DoobieEffect = new FunctionK[ConnectionIO, DoobieEffect] {
    override def apply[A](fa: ConnectionIO[A]): DoobieEffect[A] = Kleisli(_ => fa)
  }

  override def queryState(): DoobieEffect[WCState[Ctx]] =
    getWorkflow.flatMap(wf => liftIO(engine.queryState(wf)))

  override def getProgress: DoobieEffect[WIOExecutionProgress[WCState[Ctx]]] =
    getWorkflow.flatMap(wf => liftIO(engine.getProgress(wf)))

  override def getExpectedSignals: DoobieEffect[List[SignalDef[?, ?]]] =
    getWorkflow.flatMap(wf => liftIO(engine.getExpectedSignals(wf)))

  override def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): DoobieEffect[Either[UnexpectedSignal, Resp]] = {
    def processSignal(state: ActiveWorkflow[IO, Ctx]): DoobieEffect[Either[UnexpectedSignal, Resp]] = {
      liftIO(engine.handleSignal(state, signalDef, req)).flatMap {
        case SignalResult.Processed(resultF)           =>
          for {
            eventAndResp <- liftIO(resultF)
            _            <- persistEvent(eventAndResp.event)
            newStateOpt  <- liftIO(engine.handleEvent(state, eventAndResp.event))
            _            <- newStateOpt.traverse_(handleStateChange(state, _))
          } yield Right(eventAndResp.response)
        case _: SignalResult.UnexpectedSignal[?, ?, ?] =>
          Monad[DoobieEffect].pure(Left(UnexpectedSignal(signalDef)))
      }
    }
    lockState(processSignal)
  }

  override def wakeup(): DoobieEffect[Unit] = lockState(processWakeup)

  private def handleStateChange(oldState: ActiveWorkflow[IO, Ctx], newState: ActiveWorkflow[IO, Ctx]): DoobieEffect[Unit] = {
    for {
      cmds <- liftIO(engine.onStateChange(oldState, newState))
      _    <- cmds.toList.traverse_ { case WorkflowInstanceEngine.PostExecCommand.WakeUp =>
                processWakeup(newState)
              }
    } yield ()
  }

  private def processWakeup(state: ActiveWorkflow[IO, Ctx]): DoobieEffect[Unit] = {
    liftIO(engine.triggerWakeup(state)).flatMap {
      case WakeupResult.Processed(resultF) =>
        for {
          res <- liftIO(resultF)
          _   <- res match {
                   case ProcessingResult.Proceeded(event) =>
                     for {
                       _           <- persistEvent(event)
                       newStateOpt <- liftIO(engine.handleEvent(state, event))
                       _           <- newStateOpt.traverse_(handleStateChange(state, _))
                     } yield ()
                   case ProcessingResult.Delayed(_)       => Monad[DoobieEffect].unit
                   case ProcessingResult.Failed(err)      =>
                     Kleisli.liftF[ConnectionIO, LiftIO[ConnectionIO], Unit](
                       Monad[ConnectionIO].pure(logger.error("Workflow wakeup failed", err)),
                     )
                 }
        } yield ()
      case _: WakeupResult.Noop[?, ?]      => Monad[DoobieEffect].unit
    }
  }

  private def getWorkflow: DoobieEffect[ActiveWorkflow[IO, Ctx]] = {
    Kleisli(connLiftIO =>
      storage
        .getEvents(id)
        .evalFold(baseWorkflow)((state, event) => connLiftIO.liftIO(engine.processEvent(state, event)))
        .compile
        .lastOrError,
    )
  }

  private def liftIO[A](ioa: IO[A]): DoobieEffect[A] = Kleisli(_.liftIO(ioa))

  private def persistEvent(event: WCEvent[Ctx]): DoobieEffect[Unit] = Kleisli(_ => storage.saveEvent(id, event))

  private def lockState[T](update: ActiveWorkflow[IO, Ctx] => DoobieEffect[T]): DoobieEffect[T] =
    storage.lockWorkflow(id).mapK(connIOToDoobieEffect).use(_ => getWorkflow.flatMap(update))

  extension [A](opt: Option[A]) {
    def traverse_(f: A => DoobieEffect[Unit]): DoobieEffect[Unit] = opt match {
      case Some(a) => f(a)
      case None    => Monad[DoobieEffect].unit
    }
  }
}
