package workflows4s.runtime

import com.typesafe.scalalogging.StrictLogging
import workflows4s.effect.Effect
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine.PostExecCommand
import workflows4s.wio.*
import workflows4s.wio.internal.{SignalResult, WakeupResult}
import workflows4s.wio.model.WIOExecutionProgress

/** Base trait for workflow instances.
  *
  * @tparam F
  *   The effect type for this workflow instance (e.g., IO, Result)
  * @tparam EngineF
  *   The effect type used by the engine (often IO). When F != EngineF, implement liftEngineEffect to transform.
  * @tparam Ctx
  *   The workflow context type
  */
trait WorkflowInstanceBase[F[_], EngineF[_], Ctx <: WorkflowContext] extends WorkflowInstance[F, WCState[Ctx]] with StrictLogging {

  protected given E: Effect[F]
  protected given EngineE: Effect[EngineF]

  protected def persistEvent(event: WCEvent[Ctx]): F[Unit]
  protected def updateState(newState: ActiveWorkflow[Ctx]): F[Unit]

  protected def lockState[T](update: ActiveWorkflow[Ctx] => F[T]): F[T]
  // this could theoretically be implemented in terms of `updateState` but we dont want to lock anything unnecessarly
  protected def getWorkflow: F[ActiveWorkflow[Ctx]]
  protected def engine: WorkflowInstanceEngine[EngineF]

  /** Transform engine effect to instance effect. Override when F != EngineF. */
  protected def liftEngineEffect[A](fa: EngineF[A]): F[A]

  override def queryState(): F[WCState[Ctx]] = E.flatMap(getWorkflow)(x => liftEngineEffect(engine.queryState(x)))

  override def getProgress: F[WIOExecutionProgress[WCState[Ctx]]] = E.flatMap(getWorkflow)(x => liftEngineEffect(engine.getProgress(x)))

  override def getExpectedSignals: F[List[SignalDef[?, ?]]] = E.flatMap(getWorkflow)(x => liftEngineEffect(engine.getExpectedSignals(x)))

  override def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): F[Either[WorkflowInstance.UnexpectedSignal, Resp]] = {
    def processSignal(state: ActiveWorkflow[Ctx]): F[Either[WorkflowInstance.UnexpectedSignal, Resp]] = {
      E.flatMap(liftEngineEffect(engine.handleSignal(state, signalDef, req))) { result =>
        if !result.hasEffect then E.pure(Left(WorkflowInstance.UnexpectedSignal(signalDef)))
        else {
          val processed = result.asInstanceOf[SignalResult.Processed[EngineF, WCEvent[Ctx], Resp]]
          E.flatMap(liftEngineEffect(processed.resultIO)) { eventAndResp =>
            E.flatMap(persistEvent(eventAndResp.event)) { _ =>
              E.flatMap(liftEngineEffect(engine.handleEvent(state, eventAndResp.event))) { newStateOpt =>
                E.flatMap(newStateOpt.fold(E.unit)(updateState)) { _ =>
                  E.map(handleStateChange(state, newStateOpt))(_ => Right(eventAndResp.response))
                }
              }
            }
          }
        }
      }
    }
    lockState(processSignal)
  }

  override def wakeup(): F[Unit] = lockState(processWakeup)

  private def handleStateChange(oldState: ActiveWorkflow[Ctx], newStateOpt: Option[ActiveWorkflow[Ctx]]): F[Unit] = {
    newStateOpt match {
      case Some(newState) =>
        E.flatMap(liftEngineEffect(engine.onStateChange(oldState, newState))) { cmds =>
          E.traverse_(cmds.toList) { case PostExecCommand.WakeUp =>
            processWakeup(newState)
          }
        }
      case None           => E.unit
    }
  }

  private def processWakeup(state: ActiveWorkflow[Ctx]): F[Unit] = {
    E.flatMap(liftEngineEffect(engine.triggerWakeup(state))) { result =>
      if !result.hasEffect then E.unit
      else {
        val processed = result.asInstanceOf[WakeupResult.Processed[EngineF, WCEvent[Ctx]]]
        E.flatMap(liftEngineEffect(processed.result)) { processingResult =>
          processingResult.toRaw match {
            case Left(_)    => E.unit
            case Right(evt) =>
              E.flatMap(persistEvent(evt)) { _ =>
                E.flatMap(liftEngineEffect(engine.handleEvent(state, evt))) { newStateOpt =>
                  E.flatMap(newStateOpt.fold(E.unit)(updateState)) { _ =>
                    handleStateChange(state, newStateOpt)
                  }
                }
              }
          }
        }
      }
    }
  }

  protected def recover(initialState: ActiveWorkflow[Ctx], events: Seq[WCEvent[Ctx]]): F[ActiveWorkflow[Ctx]] = {
    events.foldLeft(E.pure(initialState)) { (accF, event) =>
      E.flatMap(accF)(acc => liftEngineEffect(engine.processEvent(acc, event)))
    }
  }
}
