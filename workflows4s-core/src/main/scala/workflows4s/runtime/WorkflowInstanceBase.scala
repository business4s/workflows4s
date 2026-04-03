package workflows4s.runtime

import cats.Monad
import cats.effect.{IO, LiftIO}
import cats.syntax.all.*
import com.typesafe.scalalogging.StrictLogging
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine.PostExecCommand
import workflows4s.wio.*
import workflows4s.wio.internal.{SignalResult, WakeupResult}
import workflows4s.wio.model.WIOExecutionProgress

import scala.util.chaining.scalaUtilChainingOps

trait WorkflowInstanceBase[F[_], Ctx <: WorkflowContext] extends WorkflowInstance[F, WCState[Ctx]] with StrictLogging {

  protected given fMonad: Monad[F]
  protected given liftIO: LiftIO[F]

  protected def persistEvent(event: WCEvent[Ctx]): F[Unit]
  protected def updateState(newState: ActiveWorkflow[IO, Ctx]): F[Unit]

  protected def lockState[T](update: ActiveWorkflow[IO, Ctx] => F[T]): F[T]
  // this could theoretically be implemented in terms of `updateState` but we dont want to lock anything unnecessarly
  protected def getWorkflow: F[ActiveWorkflow[IO, Ctx]]
  protected def engine: WorkflowInstanceEngine[IO]

  override def queryState(): F[WCState[Ctx]] = getWorkflow.flatMap(x => engine.queryState(x).pipe(liftIO.liftIO))

  override def getProgress: F[WIOExecutionProgress[WCState[Ctx]]] = getWorkflow.flatMap(x => engine.getProgress(x).pipe(liftIO.liftIO))

  override def getExpectedSignals(includeRedeliverable: Boolean = false): F[List[SignalDef[?, ?]]] =
    getWorkflow.flatMap(x => engine.getExpectedSignals(x, includeRedeliverable).pipe(liftIO.liftIO))

  override def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): F[Either[WorkflowInstance.UnexpectedSignal, Resp]] = {
    def processSignal(state: ActiveWorkflow[IO, Ctx]): F[Either[WorkflowInstance.UnexpectedSignal, Resp]] = {
      for {
        resultOpt <- engine.handleSignal(state, signalDef, req).pipe(liftIO.liftIO)
        result    <- resultOpt match {
                       case SignalResult.Processed(resultIO) =>
                         for {
                           eventAndResp <- resultIO.pipe(liftIO.liftIO)
                           _            <- persistEvent(eventAndResp.event)
                           newStateOpt  <- engine.handleEvent(state, eventAndResp.event).to[IO].pipe(liftIO.liftIO)
                           _            <- newStateOpt.traverse_(updateState)
                           _            <- handleStateChange(state, newStateOpt)
                         } yield Right(eventAndResp._2)
                       case SignalResult.Redelivered(resp)   =>
                         // Redelivery: no event to persist, just return the reconstructed response
                         Right(resp).pure[F]
                       case SignalResult.UnexpectedSignal()  => Left(WorkflowInstance.UnexpectedSignal(signalDef)).pure[F]
                     }
      } yield result
    }
    lockState(processSignal)
  }

  override def wakeup(): F[Unit] = lockState(processWakeup)

  private def handleStateChange(oldState: ActiveWorkflow[IO, Ctx], newStateOpt: Option[ActiveWorkflow[IO, Ctx]]): F[Unit] = {
    newStateOpt match {
      case Some(newState) =>
        for {
          cmds <- engine.onStateChange(oldState, newState).pipe(liftIO.liftIO)
          _    <- cmds.toList.traverse({ case PostExecCommand.WakeUp =>
                    processWakeup(newState)
                  })
        } yield ()
      case None           => fMonad.unit
    }
  }

  private def processWakeup(state: ActiveWorkflow[IO, Ctx]) = {
    for {
      resultOpt <- engine.triggerWakeup(state).pipe(liftIO.liftIO)
      _         <- resultOpt match {
                     case WakeupResult.Processed(resultIO) =>
                       for {
                         retryOrEvent <- resultIO.pipe(liftIO.liftIO)
                         eventOpt      = retryOrEvent match {
                                           case WakeupResult.ProcessingResult.Failed(_, event) => event
                                           case WakeupResult.ProcessingResult.Proceeded(event) => event.some
                                         }
                         newStateOpt  <- eventOpt.flatTraverse { event =>
                                           for {
                                             _           <- persistEvent(event)
                                             newStateOpt <- engine.handleEvent(state, event).to[IO].pipe(liftIO.liftIO)
                                             _           <- newStateOpt.traverse_(updateState)
                                             _           <- handleStateChange(state, newStateOpt)
                                           } yield newStateOpt
                                         }

                       } yield newStateOpt
                     case WakeupResult.Noop()              => None.pure[F]
                   }
    } yield ()
  }

  protected def recover(initialState: ActiveWorkflow[IO, Ctx], events: Seq[WCEvent[Ctx]]): F[ActiveWorkflow[IO, Ctx]] = {
    events
      .foldLeftM(initialState)(engine.processEvent)
      .to[IO]
      .pipe(liftIO.liftIO)
  }
}
