package workflows4s.web.api.server

import io.circe.Encoder
import workflows4s.runtime.WorkflowRuntime
import workflows4s.wio.WorkflowContext

case class WorkflowEntry[F[_], Ctx <: WorkflowContext](
    name: String,
    runtime: WorkflowRuntime[F, Ctx],
    stateEncoder: Encoder[workflows4s.wio.WCState[Ctx]],
    signalSupport: SignalSupport,
) {
  def id: String = runtime.templateId
}
