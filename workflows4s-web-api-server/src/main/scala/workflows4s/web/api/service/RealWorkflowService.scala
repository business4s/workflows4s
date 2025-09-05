package workflows4s.web.api.service

import cats.effect.IO
import io.circe.Encoder
import workflows4s.runtime.WorkflowRuntime
import workflows4s.web.api.model.*
import workflows4s.wio.{WCState, WorkflowContext}
import workflows4s.wio.model.{WIOExecutionProgress, WIOModel}

class RealWorkflowService(
    workflowEntries: List[RealWorkflowService.WorkflowEntry[?]],
) extends WorkflowApiService {

  def listDefinitions(): IO[List[WorkflowDefinition]] =
    IO.pure(workflowEntries.map(e => WorkflowDefinition(e.id, e.name)))

  def getDefinition(id: String): IO[WorkflowDefinition] =
    findEntry(id).map(e => WorkflowDefinition(e.id, e.name))

  def getInstance(definitionId: String, instanceId: String): IO[WorkflowInstance] =
    for {
      entry    <- findEntry(definitionId)
      instance <- getRealInstance(entry, instanceId)
    } yield instance

  def getProgress(definitionId: String, instanceId: String): IO[ProgressResponse] =
    for {
      entry <- findEntry(definitionId)
      json  <- getRealInstanceProgressJson(entry, instanceId)
    } yield json

  def createTestInstance(definitionId: String): IO[WorkflowInstance] =
    for {
      _ <- findEntry(definitionId)
    } yield {
      val testInstanceId = s"test-instance-${System.currentTimeMillis()}"
      WorkflowInstance(
        id = testInstanceId,
        definitionId = definitionId,
        status = InstanceStatus.Running,
        state = None,
      )
    }

  private def findEntry(definitionId: String): IO[RealWorkflowService.WorkflowEntry[?]] =
    IO.fromOption(workflowEntries.find(_.id == definitionId))(new Exception(s"Definition not found: $definitionId"))

  private def progressToStatus(progress: WIOExecutionProgress[?]): InstanceStatus =
    progress.result match {
      case Some(result) =>
        result.value match {
          case Right(_) => InstanceStatus.Completed
          case Left(_)  => InstanceStatus.Failed
        }
      case None         => InstanceStatus.Running
    }

  private def getRealInstance[Ctx <: WorkflowContext](
      entry: RealWorkflowService.WorkflowEntry[Ctx],
      instanceId: String,
  ): IO[WorkflowInstance] = {
    for {
      workflowInstance <- entry.runtime.createInstance(instanceId)
      currentState     <- workflowInstance.queryState()
      progress         <- workflowInstance.getProgress
    } yield WorkflowInstance(
      id = instanceId,
      definitionId = entry.id,
      status = progressToStatus(progress),
      state = Some(entry.stateEncoder(currentState)),
    )
  }

  private def getRealProgress[Ctx <: WorkflowContext](
      entry: RealWorkflowService.WorkflowEntry[Ctx],
      instanceId: String,
  ): IO[WIOExecutionProgress[WCState[Ctx]]] = {

    for {
      workflowInstance <- entry.runtime.createInstance(instanceId)
      progress         <- workflowInstance.getProgress
    } yield progress
  }

  private def getRealInstanceProgressJson[Ctx <: WorkflowContext](
      entry: RealWorkflowService.WorkflowEntry[Ctx],
      instanceId: String,
  ): IO[ProgressResponse] = {
    for {
      progress <- getRealProgress(entry, instanceId)
    } yield ProgressResponse(
      progressType = "Sequence",
      isCompleted = progress.result.isDefined,
      steps = convertProgressToSteps(progress),
      mermaidUrl = generateMermaidUrl(progress),
    )
  }

  private def generateMermaidUrl[Ctx <: WorkflowContext](progress: WIOExecutionProgress[WCState[Ctx]]): String = {
    import workflows4s.mermaid.MermaidRenderer
    val flowchart = MermaidRenderer.renderWorkflow(progress)
    flowchart.toViewUrl
  }

  private def convertProgressToSteps[Ctx <: WorkflowContext](progress: WIOExecutionProgress[WCState[Ctx]]): List[ProgressStep] = {
    progress match {
      case WIOExecutionProgress.Sequence(steps) =>
        steps.zipWithIndex.map { case (step, index) =>
          ProgressStep(
            stepType = step.getClass.getSimpleName.replace("$", ""),
            meta = ProgressStepMeta(
              name = extractNameFromModel(step.toModel),
              signalName = extractSignalNameFromModel(step.toModel),
              operationName = extractOperationNameFromModel(step.toModel),
              error = extractErrorFromModel(step.toModel),
              description = extractDescriptionFromModel(step.toModel),
            ),
            result = step.result.map(res =>
              ProgressStepResult(
                status = if res.value.isRight then "Completed" else "Failed",
                index = res.index,
                state = res.value.toOption.map(_.toString),
              ),
            ),
          )
        }.toList

      case WIOExecutionProgress.HandleError(base, handler, meta, result) =>
        convertProgressToSteps(base)

      case single =>
        List(
          ProgressStep(
            stepType = single.getClass.getSimpleName.replace("$", ""),
            meta = ProgressStepMeta(
              name = extractNameFromModel(single.toModel),
              signalName = extractSignalNameFromModel(single.toModel),
              operationName = extractOperationNameFromModel(single.toModel),
              error = extractErrorFromModel(single.toModel),
              description = extractDescriptionFromModel(single.toModel),
            ),
            result = single.result.map(res =>
              ProgressStepResult(
                status = if res.value.isRight then "Completed" else "Failed",
                index = res.index,
                state = res.value.toOption.map(_.toString),
              ),
            ),
          ),
        )
    }
  }

  private def extractNameFromModel(model: WIOModel): Option[String] = model match {
    case WIOModel.RunIO(meta)        => meta.name
    case WIOModel.HandleSignal(meta) => meta.operationName
    case WIOModel.Pure(meta)         => meta.name
    case WIOModel.Timer(meta)        => meta.name
    case WIOModel.Fork(_, meta)      => meta.name
    case _                           => None
  }

  private def extractSignalNameFromModel(model: WIOModel): Option[String] = model match {
    case WIOModel.HandleSignal(meta) => Some(meta.signalName)
    case _                           => None
  }

  private def extractOperationNameFromModel(model: WIOModel): Option[String] = model match {
    case WIOModel.HandleSignal(meta) => meta.operationName
    case _                           => None
  }

  private def extractErrorFromModel(model: WIOModel): Option[String] = model match {
    case WIOModel.RunIO(meta)        => meta.error.map(_.name)
    case WIOModel.HandleSignal(meta) => meta.error.map(_.name)
    case WIOModel.Pure(meta)         => meta.error.map(_.name)
    case WIOModel.Dynamic(meta)      => meta.error.map(_.name)
    case _                           => None
  }

  private def extractDescriptionFromModel(model: WIOModel): Option[String] = model match {
    case WIOModel.RunIO(meta) => meta.description
    case _                    => None
  }

}

object RealWorkflowService {
  case class WorkflowEntry[Ctx <: WorkflowContext](
      id: String,
      name: String,
      runtime: WorkflowRuntime[IO, Ctx],
      stateEncoder: Encoder[workflows4s.wio.WCState[Ctx]],
  )
}
