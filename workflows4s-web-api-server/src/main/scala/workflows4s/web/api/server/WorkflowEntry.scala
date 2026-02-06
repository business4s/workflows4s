package workflows4s.web.api.server

import io.circe.Encoder
import workflows4s.runtime.WorkflowRuntime
import workflows4s.wio.WorkflowContext

/** Configuration for exposing a workflow through the web API. Bundles the runtime with the metadata
  * and codecs the API layer needs to serialize state and deliver signals.
  */
case class WorkflowEntry[F[_], Ctx <: WorkflowContext](
    name: String,
    description: Option[String],
    runtime: WorkflowRuntime[F, Ctx],
    stateEncoder: Encoder[workflows4s.wio.WCState[Ctx]],
    signalSupport: SignalSupport,
) {
  def id: String = runtime.templateId
}
