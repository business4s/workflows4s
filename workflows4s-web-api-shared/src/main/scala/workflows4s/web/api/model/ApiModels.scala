package workflows4s.web.api.model

import io.circe.{Codec, Json}

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
    state: Option[Json] = None,
    createdAt: Option[String] = None,
    updatedAt: Option[String] = None,
) derives Codec.AsObject

enum InstanceStatus derives Codec.AsObject {
  case Running, Completed, Failed, Paused
}

case class ProgressResponse(
    progressType: String,
    isCompleted: Boolean,
    steps: List[ProgressStep]
) derives Codec.AsObject

case class ProgressStep(
    stepType: String,
    meta: ProgressStepMeta,
    result: Option[ProgressStepResult]
) derives Codec.AsObject

case class ProgressStepMeta(
    name: Option[String],
    signalName: Option[String],
    operationName: Option[String],
    error: Option[String],
    description: Option[String],
) derives Codec.AsObject

case class ProgressStepResult(
    status: String, // "Completed", "Failed", "Running"
    index: Int,
    state: Option[String],
) derives Codec.AsObject

case class ErrorInfo(
    name: String
) derives Codec.AsObject
