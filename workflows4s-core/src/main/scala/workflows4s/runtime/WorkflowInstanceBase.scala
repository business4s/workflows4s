package workflows4s.runtime

import com.typesafe.scalalogging.StrictLogging
import workflows4s.effect.Effect
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine.PostExecCommand
import workflows4s.wio.*
import workflows4s.wio.internal.{SignalResult, WakeupResult}
import workflows4s.wio.model.WIOExecutionProgress

trait WorkflowInstanceBase[F[_], Ctx <: WorkflowContext] extends WorkflowInstance[F, WCState[Ctx]] with StrictLogging {

  protected given E: Effect[F]

  protected def persistEvent(event: WCEvent[Ctx]): F[Unit]
  protected def updateState(newState: ActiveWorkflow[Ctx]): F[Unit]

  protected def lockState[T](update: ActiveWorkflow[Ctx] => F[T]): F[T]
  // this could theoretically be implemented in terms of `updateState` but we dont want to lock anything unnecessarly
  protected def getWorkflow: F[ActiveWorkflow[Ctx]]
  protected def engine: WorkflowInstanceEngine[F]

  override def queryState(): F[WCState[Ctx]] = E.flatMap(getWorkflow)(x => engine.queryState(x))

  override def getProgress: F[WIOExecutionProgress[WCState[Ctx]]] = E.flatMap(getWorkflow)(x => engine.getProgress(x))

  override def getExpectedSignals: F[List[SignalDef[?, ?]]] = E.flatMap(getWorkflow)(x => engine.getExpectedSignals(x))

  override def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): F[Either[WorkflowInstance.UnexpectedSignal, Resp]] = {
    def processSignal(state: ActiveWorkflow[Ctx]): F[Either[WorkflowInstance.UnexpectedSignal, Resp]] = {
      E.flatMap(engine.handleSignal(state, signalDef, req)) { result =>
        if !result.hasEffect then E.pure(Left(WorkflowInstance.UnexpectedSignal(signalDef)))
        else {
          val processed = result.asInstanceOf[SignalResult.Processed[F, WCEvent[Ctx], Resp]]
          E.flatMap(processed.resultIO) { eventAndResp =>
            E.flatMap(persistEvent(eventAndResp.event)) { _ =>
              E.flatMap(engine.handleEvent(state, eventAndResp.event)) { newStateOpt =>
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
        E.flatMap(engine.onStateChange(oldState, newState)) { cmds =>
          E.traverse_(cmds.toList) { case PostExecCommand.WakeUp =>
            processWakeup(newState)
          }
        }
      case None           => E.unit
    }
  }

  private def processWakeup(state: ActiveWorkflow[Ctx]): F[Unit] = {
    E.flatMap(engine.triggerWakeup(state)) { result =>
      if !result.hasEffect then E.unit
      else {
        val processed = result.asInstanceOf[WakeupResult.Processed[F, WCEvent[Ctx]]]
        E.flatMap(processed.result) { processingResult =>
          processingResult.toRaw match {
            case Left(_)      => E.unit
            case Right(event) =>
              E.flatMap(persistEvent(event)) { _ =>
                E.flatMap(engine.handleEvent(state, event)) { newStateOpt =>
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
      E.flatMap(accF)(acc => engine.processEvent(acc, event))
    }
  }
}
