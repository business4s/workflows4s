package workflows4s.web.api.service

import cats.effect.IO  
import workflows4s.web.api.model.*
import workflows4s.runtime.WorkflowRuntime
import workflows4s.wio.WorkflowContext
import io.circe.Encoder
import io.circe.Json
import cats.syntax.either.*

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

  private def getRealInstance(
    entry: RealWorkflowService.WorkflowEntry[?, ?], 
    instanceId: String
  ): IO[Either[String, WorkflowInstance]] = {
    
    val typedEntry = entry.asInstanceOf[RealWorkflowService.WorkflowEntry[String, WorkflowContext]]
    
    val ioResult = for {
      workflowInstance <- typedEntry.runtime.createInstance(instanceId)
      currentState <- workflowInstance.queryState()
      progress <- workflowInstance.getProgress
    } yield {
      WorkflowInstance(
        id = instanceId,
        definitionId = entry.id,
        state = Some(serializeStateToJson(currentState, typedEntry.stateEncoder.asInstanceOf[Encoder[Any]]))
      )
    }  
    
    ioResult.attempt.map(_.leftMap(_.getMessage))  
  }

 

  private def serializeStateToJson(state: Any, encoder: Encoder[Any]): Json = {
    try {
      encoder(state)
    } catch {
      case ex: Exception => 
        Json.obj(
          "error" -> Json.fromString("Failed to serialize state"),
          "state_type" -> Json.fromString(state.getClass.getSimpleName),
          "raw_state" -> Json.fromString(state.toString)
        )
    }
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