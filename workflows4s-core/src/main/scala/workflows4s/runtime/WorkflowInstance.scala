package workflows4s.runtime

import workflows4s.runtime.WorkflowInstance.UnexpectedSignal
import workflows4s.wio.{SignalDef, SignalRouter}
import workflows4s.wio.model.WIOExecutionProgress

/** Handle to a single running workflow. Provides operations to query state, deliver signals, and trigger wakeups.
  *
  * Obtained from [[WorkflowRuntime.createInstance]]. All operations are safe to call concurrently —
  * the underlying implementation handles locking and event persistence.
  */
trait WorkflowInstance[F[_], State] {

  def id: WorkflowInstanceId

  def queryState(): F[State]

  /** Delivers a signal to the workflow. Returns `Left` if the workflow is not currently awaiting this signal. */
  def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): F[Either[UnexpectedSignal, Resp]]
  def deliverRoutedSignal[Req, Resp, Key](
      signalRouter: SignalRouter.Sender[Key],
      routingKey: Key,
      signalDef: SignalDef[Req, Resp],
      req: Req,
  ): F[Either[UnexpectedSignal, Resp]] = {
    val w = signalRouter.wrap(routingKey, req, signalDef)
    deliverSignal(w.sigDef, w.req)
  }

  /** Triggers progression (IO, timers). Called by [[workflows4s.runtime.wakeup.KnockerUpper]] or manually. */
  def wakeup(): F[Unit]

  /** Returns the workflow's execution tree with the current state at each node. Useful for visualization. */
  def getProgress: F[WIOExecutionProgress[State]]

  def getExpectedSignals(includeRedeliverable: Boolean = false): F[List[SignalDef[?, ?]]]

}

object WorkflowInstance {

  case class UnexpectedSignal(signalDef: SignalDef[?, ?])

}
