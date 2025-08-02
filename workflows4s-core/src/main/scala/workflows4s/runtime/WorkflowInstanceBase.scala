package workflows4s.runtime

import cats.Monad
import cats.effect.{IO, LiftIO}
import cats.syntax.all.*
import com.typesafe.scalalogging.StrictLogging
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine.PostExecCommand
import workflows4s.wio.*
import workflows4s.wio.model.WIOExecutionProgress

import scala.util.chaining.scalaUtilChainingOps

trait WorkflowInstanceBase[F[_], Ctx <: WorkflowContext] extends WorkflowInstance[F, WCState[Ctx]] with StrictLogging {

  protected given fMonad: Monad[F]
  protected given liftIO: LiftIO[F]

  protected def persistEvent(event: WCEvent[Ctx]): F[Unit]
  protected def updateState(newState: ActiveWorkflow[Ctx]): F[Unit]

  protected def lockState[T](update: ActiveWorkflow[Ctx] => F[T]): F[T]
  // this could theoretically be implemented in terms of `updateState` but we dont want to lock anything unnecessarly
  protected def getWorkflow: F[ActiveWorkflow[Ctx]]
  protected def engine: WorkflowInstanceEngine

  override def queryState(): F[WCState[Ctx]] = getWorkflow.flatMap(x => engine.queryState(x).pipe(liftIO.liftIO))

  override def getProgress: F[WIOExecutionProgress[WCState[Ctx]]] = getWorkflow.flatMap(x => engine.getProgress(x).pipe(liftIO.liftIO))

  override def getExpectedSignals: F[List[SignalDef[?, ?]]] = getWorkflow.flatMap(x => engine.getExpectedSignals(x).pipe(liftIO.liftIO))

  override def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): F[Either[WorkflowInstance.UnexpectedSignal, Resp]] = {
    def processSignal(state: ActiveWorkflow[Ctx]): F[Either[WorkflowInstance.UnexpectedSignal, Resp]] = {
      for {
        resultOpt <- engine.handleSignal(state, signalDef, req).pipe(liftIO.liftIO)
        result    <- resultOpt match {
                       case Some(resultIO) =>
                         for {
                           eventAndResp <- resultIO.pipe(liftIO.liftIO)
                           _            <- persistEvent(eventAndResp._1)
                           newStateOpt  <- engine.handleEvent(state, eventAndResp._1).to[IO].pipe(liftIO.liftIO)
                           _            <- newStateOpt.traverse_(updateState)
                           _            <- handleStateChange(state, newStateOpt)
                         } yield Right(eventAndResp._2)
                       case None           => Left(WorkflowInstance.UnexpectedSignal(signalDef)).pure[F]
                     }
      } yield result
    }
    lockState(processSignal)
  }

  override def wakeup(): F[Unit] = lockState(processWakeup)

  private def handleStateChange(oldState: ActiveWorkflow[Ctx], newStateOpt: Option[ActiveWorkflow[Ctx]]): F[Unit] = {
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

  private def processWakeup(state: ActiveWorkflow[Ctx]) = {
    for {
      resultOpt <- engine.triggerWakeup(state).pipe(liftIO.liftIO)
      _         <- resultOpt match {
                     case Some(retryOrEventIO) =>
                       for {
                         retryOrEvent <- retryOrEventIO.pipe(liftIO.liftIO)
                         newStateOpt  <- retryOrEvent match {
                                           case Left(_)      => None.pure[F]
                                           case Right(event) =>
                                             for {
                                               _           <- persistEvent(event)
                                               newStateOpt <- engine.handleEvent(state, event).to[IO].pipe(liftIO.liftIO)
                                               _           <- newStateOpt.traverse_(updateState)
                                               _           <- handleStateChange(state, newStateOpt)
                                             } yield newStateOpt
                                         }
                       } yield newStateOpt
                     case None                 => None.pure[F]
                   }
    } yield ()
  }

  protected def recover(initialState: ActiveWorkflow[Ctx], events: Seq[WCEvent[Ctx]]): F[ActiveWorkflow[Ctx]] = {
    events
      .foldLeftM(initialState)(engine.processEvent)
      .to[IO]
      .pipe(liftIO.liftIO)
  }
}
