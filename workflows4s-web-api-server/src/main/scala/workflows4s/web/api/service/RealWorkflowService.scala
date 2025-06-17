package workflows4s.web.api.service

import scala.concurrent.Future
import workflows4s.web.api.model.*
import workflows4s.runtime.WorkflowRuntime
import workflows4s.wio.WorkflowContext
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Encoder
import io.circe.Json
import cats.syntax.either.*

class RealWorkflowService(
  workflowEntries: List[RealWorkflowService.WorkflowEntry[?, ?]]
) extends WorkflowApiService {

  def listDefinitions(): Future[Either[String, List[WorkflowDefinition]]] = {
    val definitions = workflowEntries.map(entry => 
      WorkflowDefinition(
        id = entry.id,
        name = entry.name
      )
    )
    Future.successful(Right(definitions))
  }

  def getDefinition(id: String): Future[Either[String, WorkflowDefinition]] = {
    workflowEntries.find(_.id == id) match {
      case Some(entry) => 
        val definition = WorkflowDefinition(
          id = entry.id,
          name = entry.name
        )
        Future.successful(Right(definition))
      case None => 
        Future.successful(Left(s"Workflow definition not found: $id"))
    }
  }

 

  def getInstance(definitionId: String, instanceId: String): Future[Either[String, WorkflowInstance]] = {
    workflowEntries.find(_.id == definitionId) match {
      case Some(entry) =>
        getRealInstance(entry, instanceId)
      case None =>
        Future.successful(Left(s"Definition not found: $definitionId"))
    }
  }
 
  private def getRealInstance(
    entry: RealWorkflowService.WorkflowEntry[?, ?], 
    instanceId: String
  ): Future[Either[String, WorkflowInstance]] = {
    
    val typedEntry = entry.asInstanceOf[RealWorkflowService.WorkflowEntry[String, WorkflowContext]]
    val workflowId = typedEntry.parseId(instanceId)
    
    val ioResult = for {
      workflowInstance <- typedEntry.runtime.createInstance(workflowId)
      currentState <- workflowInstance.queryState()
      progress <- workflowInstance.getProgress
    } yield {
      WorkflowInstance(
        id = instanceId,
        definitionId = entry.id,
        currentStep = extractCurrentStep(currentState),
        state = Some(serializeStateToJson(currentState, typedEntry.stateEncoder.asInstanceOf[Encoder[Any]]))
      )
    }
    
    ioResult.attempt
      .map(_.leftMap(_.getMessage))
      .unsafeToFuture()
      .asInstanceOf[Future[Either[String, WorkflowInstance]]]
  }

  private def extractCurrentStep(state: Any): Option[String] = {
    try {
      val stateStr = state.toString
      if (stateStr.contains("step") || stateStr.contains("Step")) {
        Some("active-step")
      } else {
        Some("unknown-step")
      }
    } catch {
      case _: Exception => Some("unknown-step")
    }
  }

  private def serializeStateToJson(state: Any, encoder: Encoder[Any]): Json = {
    try {
      encoder(state)
    } catch {
      case ex: Exception => 
        Json.obj(
          "serialization_error" -> Json.fromString(ex.getMessage),
          "raw_state" -> Json.fromString(state.toString),
          "state_type" -> Json.fromString(state.getClass.getSimpleName)
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