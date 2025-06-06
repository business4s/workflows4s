package workflows4s.example.uiexample

import cats.effect.IO
import workflows4s.example.courseregistration.CourseRegistrationWorkflow
import workflows4s.example.docs.pullrequest.PullRequestWorkflow
import workflows4s.example.docs.wakeups.common.MyWorkflowCtx
import workflows4s.runtime
import workflows4s.runtime.WorkflowRuntime
import workflows4s.web.api.endpoints.WorkflowEndpoints
import workflows4s.web.api.model.{InstancesFilter, PaginatedResponse, WorkflowDefinition, WorkflowInstance}
import workflows4s.web.api.service.WorkflowApiService
import workflows4s.wio.{WCState, WorkflowContext}

import scala.concurrent.Future

class Main {

  def main(args: Array[String]): Unit = {
    
    for {
      _ <- serverApi(
             CourseRegistrationWorkflow.workflow,
             PullRequestWorkflow.workflow,
           )
      _ <- serveUI()
    } yield ()

  }

  def serverApi(): IO[Unit] = {
    val x = WorkflowEndpoints.allEndpoints
  }
  import RealWorkflowService.WorkflowEntry

  class RealWorkflowService(runtimes: List[WorkflowEntry[?, ?]], registry: ApiWorkflowRegistry) extends WorkflowApiService {
    def listDefinitions(): Future[Either[String, List[WorkflowDefinition]]]
    def getDefinition(id: String): Future[Either[String, WorkflowDefinition]] = {
      val definition = runtimes.find(_.id == definitionId).get
      
    }
    def getInstance(definitionId: String, instanceId: String): Future[Either[String, WorkflowInstance]] = {
      val definition = runtimes.find(_.id == definitionId).get
      for {
        instance <- getInstance(definition, instanceId)
        state    <- instance.queryState()
      } yield WorkflowInstance(
        instanceId,
        definitionId,
        state.toString,
      )
    }

    def getInstance[Id, Ctx <: WorkflowContext](
        definition: WorkflowEntry[Id, Ctx],
        instanceId: String,
    ): IO[runtime.WorkflowInstance[IO, WCState[Ctx]]] = {
      val runtime: WorkflowRuntime[IO, Ctx, Id] = definition.runtime
      val parsedId                              = definition.parseId(instanceId)
      runtime.createInstance(parsedId)
    }
  }

  object RealWorkflowService {

    case class WorkflowEntry[WorkflowId, Ctx <: WorkflowContext](
        id: String,
        name: String,
        runtime: WorkflowRuntime[IO, Ctx, WorkflowId],
        parseId: String => WorkflowId,
    )

  }

  trait ApiWorkflowRegistry {}

}
