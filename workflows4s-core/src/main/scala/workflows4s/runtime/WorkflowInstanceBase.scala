package workflows4s.runtime

import cats.Monad
import cats.syntax.all.*
import com.typesafe.scalalogging.StrictLogging
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine.PostExecCommand
import workflows4s.wio.*
import workflows4s.wio.internal.{SignalResult, WakeupResult}
import workflows4s.wio.model.WIOExecutionProgress

/** Base implementation of [[WorkflowInstance]] that handles signal delivery, wakeups, event persistence and recovery.
  *
  * @tparam F
  *   the effect type for both runtime operations and engine operations
  * @tparam Ctx
  *   the workflow context
  */
trait WorkflowInstanceBase[F[_], Ctx <: WorkflowContext] extends WorkflowInstance[F, WCState[Ctx]] with StrictLogging {

  protected given fMonad: Monad[F]

  protected def persistEvent(event: WCEvent[Ctx]): F[Unit]
  protected def updateState(newState: ActiveWorkflow[Ctx]): F[Unit]

  protected def lockState[T](update: ActiveWorkflow[Ctx] => F[T]): F[T]
  // this could theoretically be implemented in terms of `updateState` but we dont want to lock anything unnecessarly
  protected def getWorkflow: F[ActiveWorkflow[Ctx]]
  protected def engine: WorkflowInstanceEngine[F, Ctx]

  override def queryState(): F[WCState[Ctx]] = getWorkflow.flatMap(engine.queryState)

  override def getProgress: F[WIOExecutionProgress[WCState[Ctx]]] = getWorkflow.flatMap(engine.getProgress)

  override def getExpectedSignals(includeRedeliverable: Boolean = false): F[List[SignalDef[?, ?]]] =
    getWorkflow.flatMap(engine.getExpectedSignals(_, includeRedeliverable))

  override def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): F[Either[WorkflowInstance.UnexpectedSignal, Resp]] = {
    def processSignal(state: ActiveWorkflow[Ctx]): F[Either[WorkflowInstance.UnexpectedSignal, Resp]] = {
      for {
        resultOpt <- engine.handleSignal(state, signalDef, req)
        result    <- resultOpt match {
                       case SignalResult.Processed(resultF) =>
                         for {
                           eventAndResp <- resultF
                           _            <- persistEvent(eventAndResp.event)
                           newStateOpt   = engine.handleEvent(state, eventAndResp.event).unsafeRun()
                           _            <- newStateOpt.traverse_(updateState)
                           _            <- handleStateChange(state, newStateOpt)
                         } yield Right(eventAndResp._2)
                       case SignalResult.Redelivered(resp)  =>
                         Right(resp).pure[F]
                       case SignalResult.UnexpectedSignal() => Left(WorkflowInstance.UnexpectedSignal(signalDef)).pure[F]
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
          cmds <- engine.onStateChange(oldState, newState)
          _    <- cmds.toList.traverse({ case PostExecCommand.WakeUp =>
                    processWakeup(newState)
                  })
        } yield ()
      case None           => fMonad.unit
    }
  }

  private def processWakeup(state: ActiveWorkflow[Ctx]) = {
    for {
      resultOpt <- engine.triggerWakeup(state)
      _         <- resultOpt match {
                     case WakeupResult.Processed(resultF) =>
                       for {
                         retryOrEvent <- resultF
                         eventOpt      = retryOrEvent match {
                                           case WakeupResult.ProcessingResult.Failed(_, event) => event
                                           case WakeupResult.ProcessingResult.Proceeded(event) => event.some
                                         }
                         newStateOpt  <- eventOpt.flatTraverse { event =>
                                           for {
                                             _          <- persistEvent(event)
                                             newStateOpt = engine.handleEvent(state, event).unsafeRun()
                                             _          <- newStateOpt.traverse_(updateState)
                                             _          <- handleStateChange(state, newStateOpt)
                                           } yield newStateOpt
                                         }

                       } yield newStateOpt
                     case WakeupResult.Noop()             => None.pure[F]
                   }
    } yield ()
  }

  protected def recover(initialState: ActiveWorkflow[Ctx], events: Seq[WCEvent[Ctx]]): F[ActiveWorkflow[Ctx]] = {
    events
      .foldLeft(initialState)((state, event) => engine.processEvent(state, event).unsafeRun())
      .pure[F]
  }
}
