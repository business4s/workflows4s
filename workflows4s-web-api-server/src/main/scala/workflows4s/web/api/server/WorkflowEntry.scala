package workflows4s.web.api.server

import com.typesafe.scalalogging.StrictLogging
import io.circe.Encoder
import workflows4s.runtime.WorkflowRuntime
import workflows4s.wio.WorkflowContext
import workflows4s.wio.internal.SignalEvaluator

/** Configuration for exposing a workflow through the web API. Bundles the runtime with the metadata and codecs the API layer needs to serialize state
  * and deliver signals.
  */
case class WorkflowEntry[F[_], Ctx <: WorkflowContext](
    name: String,
    description: Option[String],
    runtime: WorkflowRuntime[F, Ctx],
    stateEncoder: Encoder[workflows4s.wio.WCState[Ctx]],
    signalSupport: SignalSupport,
) extends StrictLogging {
  def id: String = runtime.templateId

  locally {
    val workflowSignals = SignalEvaluator.getAllSignalDefs(runtime.workflow)
    val registeredIds   = signalSupport.getRegisteredSignalIds
    val missingSignals  = workflowSignals.filterNot(s => registeredIds.contains(s.id))

    missingSignals.foreach { sig =>
      logger.warn(s"Signal '${sig.name}' (${sig.id}) used in workflow '$name' but not registered in SignalSupport")
    }
  }
}
