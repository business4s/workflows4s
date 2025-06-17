package workflows4s.web.api.server

import workflows4s.web.api.endpoints.WorkflowEndpoints
import workflows4s.web.api.service.RealWorkflowService
import workflows4s.web.api.model.*
import scala.concurrent.Future
import io.circe.Json
import sttp.tapir.server.ServerEndpoint 
import sttp.capabilities.pekko.PekkoStreams 
import sttp.capabilities.WebSockets 
import scala.concurrent.ExecutionContext.Implicits.global  


class WorkflowServerEndpoints(workflowService: RealWorkflowService) {

  private def createTestInstanceLogic(workflowId: String): Future[Either[String, WorkflowInstance]] = {
    Future.successful {
      val (currentStep, state) = workflowId match {
        case id if id.contains("course-registration") =>
          ("waiting-for-priorities", Json.obj(
            "type" -> Json.fromString("Browsing"),
            "data" -> Json.obj(
              "studentId" -> Json.fromString("student-456"),
              "semester" -> Json.fromString("2025-spring"),
              "currentCR" -> Json.fromString("CORE"),
              "availableCourses" -> Json.arr(
                Json.fromString("CS101 - Introduction to Programming"),
                Json.fromString("MATH202 - Calculus II"),
                Json.fromString("PHYS151 - Physics I")
              ),
              "selectedPriorities" -> Json.obj(
                "CORE" -> Json.arr(Json.fromString("CS101"), Json.fromString("MATH202")),
                "ELECTIVE" -> Json.arr(Json.fromString("PHYS151"))
              ),
              "registrationDeadline" -> Json.fromString("2025-02-15T23:59:59Z"),
              "waitingApproval" -> Json.fromBoolean(true)
            )
          ))
          
        case id if id.contains("pull-request") =>
          ("code-review", Json.obj(
            "type" -> Json.fromString("UnderReview"),
            "data" -> Json.obj(
              "prNumber" -> Json.fromInt(42),
              "author" -> Json.fromString("developer-123"),
              "title" -> Json.fromString("Add user authentication feature"),
              "branch" -> Json.fromString("feature/auth-system"),
              "reviewers" -> Json.arr(
                Json.fromString("senior-dev-1"),
                Json.fromString("tech-lead-2")
              ),
              "filesChanged" -> Json.fromInt(15),
              "linesAdded" -> Json.fromInt(234),
              "linesRemoved" -> Json.fromInt(12),
              "checksStatus" -> Json.obj(
                "tests" -> Json.fromString("passed"),
                "lint" -> Json.fromString("passed"),
                "security" -> Json.fromString("pending")
              ),
              "approvals" -> Json.arr(Json.fromString("senior-dev-1")),
              "requestedChanges" -> Json.arr(),
              "mergeReady" -> Json.fromBoolean(false)
            )
          ))
          
        case _ =>
          ("initialize", Json.obj(
            "type" -> Json.fromString("Unknown"),
            "data" -> Json.obj("message" -> Json.fromString("Generic workflow state"))
          ))
      }
      
      Right(WorkflowInstance(
        id = s"test-instance-${System.currentTimeMillis()}",
        definitionId = workflowId,
        currentStep = Some(currentStep),
        state = Some(state)
      ))
    }
  }

  val endpoints = List(
    WorkflowEndpoints.listDefinitions.serverLogic(_ => workflowService.listDefinitions()), 
    
   
    WorkflowEndpoints.getInstance.serverLogic((workflowId, instanceId) => {
      // If it's a test instance, return test data with the requested ID
      if (instanceId.startsWith("test-instance-")) {
        createTestInstanceLogic(workflowId).map(_.map(instance => instance.copy(id = instanceId)))
      } else {
        // For non-test instances, return "not found" for now
        Future.successful(Left("Instance not found"))
      }
    }), 
    
  
    // WorkflowEndpoints.listInstances.serverLogic { case (workflowId, status, createdAfter, createdBefore, limit, offset) =>
    //   Future.successful(Right(PaginatedResponse(
    //     items = List.empty[WorkflowInstance],
    //     total = 0,
    //     hasMore = false
    //   )))
    // },
    
    WorkflowEndpoints.createTestInstanceEndpoint.serverLogic(createTestInstanceLogic)
  ).asInstanceOf[List[ServerEndpoint[PekkoStreams & WebSockets, Future]]]
}