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
  *   the runtime effect (persistence, locking) - e.g. `IO`, `Id`, `Kleisli[ConnectionIO, ...]`
  * @tparam G
  *   the workflow/engine effect (signal handlers, wakeups) - e.g. `IO`
  */
trait WorkflowInstanceBase[F[_], G[_], Ctx <: WorkflowContext] extends WorkflowInstance[F, WCState[Ctx]] with StrictLogging {

  protected given fMonad: Monad[F]

  /** Natural transformation that lifts engine results into the runtime effect. */
  protected def liftG: [A] => G[A] => F[A]

  protected def persistEvent(event: WCEvent[Ctx]): F[Unit]
  protected def updateState(newState: ActiveWorkflow[G, Ctx]): F[Unit]

  protected def lockState[T](update: ActiveWorkflow[G, Ctx] => F[T]): F[T]
  // this could theoretically be implemented in terms of `updateState` but we dont want to lock anything unnecessarly
  protected def getWorkflow: F[ActiveWorkflow[G, Ctx]]
  protected def engine: WorkflowInstanceEngine[G]

  override def queryState(): F[WCState[Ctx]] = getWorkflow.flatMap(x => liftG(engine.queryState(x)))

  override def getProgress: F[WIOExecutionProgress[WCState[Ctx]]] = getWorkflow.flatMap(x => liftG(engine.getProgress(x)))

  override def getExpectedSignals(includeRedeliverable: Boolean = false): F[List[SignalDef[?, ?]]] =
    getWorkflow.flatMap(x => liftG(engine.getExpectedSignals(x, includeRedeliverable)))

  override def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): F[Either[WorkflowInstance.UnexpectedSignal, Resp]] = {
    def processSignal(state: ActiveWorkflow[G, Ctx]): F[Either[WorkflowInstance.UnexpectedSignal, Resp]] = {
      for {
        resultOpt <- liftG(engine.handleSignal(state, signalDef, req))
        result    <- resultOpt match {
                       case SignalResult.Processed(resultG) =>
                         for {
                           eventAndResp <- liftG(resultG)
                           _            <- persistEvent(eventAndResp.event)
                           newStateOpt   = engine.handleEvent(state, eventAndResp.event).unsafeRun()
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

  private def handleStateChange(oldState: ActiveWorkflow[G, Ctx], newStateOpt: Option[ActiveWorkflow[G, Ctx]]): F[Unit] = {
    newStateOpt match {
      case Some(newState) =>
        for {
          cmds <- liftG(engine.onStateChange(oldState, newState))
          _    <- cmds.toList.traverse({ case PostExecCommand.WakeUp =>
                    processWakeup(newState)
                  })
        } yield ()
      case None           => fMonad.unit
    }
  }

  private def processWakeup(state: ActiveWorkflow[G, Ctx]) = {
    for {
      resultOpt <- liftG(engine.triggerWakeup(state))
      _         <- resultOpt match {
                     case WakeupResult.Processed(resultG) =>
                       for {
                         retryOrEvent <- liftG(resultG)
                         eventOpt      = retryOrEvent match {
                                           case WakeupResult.ProcessingResult.Failed(_, event) => event
                                           case WakeupResult.ProcessingResult.Proceeded(event) => event.some
                                         }
                         newStateOpt  <- eventOpt.flatTraverse { event =>
                                           for {
                                             _           <- persistEvent(event)
                                             newStateOpt  = engine.handleEvent(state, event).unsafeRun()
                                             _           <- newStateOpt.traverse_(updateState)
                                             _           <- handleStateChange(state, newStateOpt)
                                           } yield newStateOpt
                                         }

                       } yield newStateOpt
                     case WakeupResult.Noop()              => None.pure[F]
                   }
    } yield ()
  }

  protected def recover(initialState: ActiveWorkflow[G, Ctx], events: Seq[WCEvent[Ctx]]): F[ActiveWorkflow[G, Ctx]] = {
    events
      .foldLeft(initialState)((state, event) => engine.processEvent(state, event).unsafeRun())
      .pure[F]
  }
}
