package workflows4s.web.api.service

import cats.effect.IO
import cats.syntax.either.*
import io.circe.Encoder
import workflows4s.runtime.WorkflowRuntime
import workflows4s.web.api.model.*
import workflows4s.wio.WorkflowContext

class RealWorkflowService(
  workflowEntries: List[RealWorkflowService.WorkflowEntry[?, ?]]
) extends WorkflowApiService {

  def listDefinitions(): IO[Either[String, List[WorkflowDefinition]]] = {
    val definitions = workflowEntries.map(entry => 
      WorkflowDefinition(
        id = entry.id,
        name = entry.name
      )
    )
    IO.pure(Right(definitions))  
  }

  def getDefinition(id: String): IO[Either[String, WorkflowDefinition]] = { 
    workflowEntries.find(_.id == id) match {
      case Some(entry) => 
        val definition = WorkflowDefinition(
          id = entry.id,
          name = entry.name
        )
        IO.pure(Right(definition))
      case None => 
        IO.pure(Left(s"Workflow definition not found: $id"))
    }
  }

  def getInstance(definitionId: String, instanceId: String): IO[Either[String, WorkflowInstance]] = {
    workflowEntries.find(_.id == definitionId) match {
      case Some(entry) =>
        getRealInstance(entry, instanceId)
      case None =>
        IO.pure(Left(s"Definition not found: $definitionId"))
    }
  }

  private def getRealInstance[WorkflowId, Ctx <: WorkflowContext](
    entry: RealWorkflowService.WorkflowEntry[WorkflowId, Ctx], 
    instanceId: String
  ): IO[Either[String, WorkflowInstance]] = {
    val parsedId = entry.parseId(instanceId)
    val ioResult = for {
      workflowInstance <- entry.runtime.createInstance(parsedId)
      currentState <- workflowInstance.queryState()
    } yield {
      WorkflowInstance(
        id = instanceId,
        definitionId = entry.id,
        state = Some(entry.stateEncoder(currentState))
      )
    }  
    
    ioResult.attempt.map(_.leftMap(_.getMessage))  
  }

}

object RealWorkflowService {
  case class WorkflowEntry[WorkflowId, Ctx <: WorkflowContext](
    id: String,                                    
    name: String,                                  
    runtime: WorkflowRuntime[IO, Ctx, WorkflowId], 
    parseId: String => WorkflowId,
    stateEncoder: Encoder[workflows4s.wio.WCState[Ctx]]
  )
}