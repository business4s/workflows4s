package workflows4s.web.api.model

import io.circe.Codec

case class WorkflowDefinition(
    id: String,
    name: String,
    version: Option[String] = None,
    description: Option[String] = None,
) derives Codec.AsObject

case class WorkflowInstance(
    id: String,
    definitionId: String,
    status: InstanceStatus,
    state: Option[io.circe.Json] = None,
    createdAt: Option[String] = None,
    updatedAt: Option[String] = None,
) derives Codec.AsObject

enum InstanceStatus derives Codec.AsObject {
  case Running, Completed, Failed, Paused
}

case class ProgressResponse(
    progressType: String,
    isCompleted: Boolean,
    steps: List[ProgressStep],
    mermaidUrl: String,
    mermaidCode: String, // Non-optional now
) derives Codec.AsObject

case class ProgressStep(
    stepType: String,
    meta: ProgressStepMeta,
    result: Option[ProgressStepResult] = None,
) derives Codec.AsObject

case class ProgressStepMeta(
    name: Option[String] = None,
    signalName: Option[String] = None,
    operationName: Option[String] = None,
    error: Option[String] = None,
    description: Option[String] = None,
) derives Codec.AsObject

case class ProgressStepResult(
    status: String, // "Completed", "Failed", "Running"
    index: Int,
    state: Option[String] = None,
) derives Codec.AsObject

case class ErrorInfo(
    name: String,
) derives Codec.AsObject
