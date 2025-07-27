package workflows4s.example.api

import cats.effect.IO
import io.circe.{Encoder, Json}
import org.http4s.HttpRoutes
import org.http4s.server.middleware.CORS
import sttp.tapir.server.http4s.Http4sServerInterpreter
import workflows4s.example.courseregistration.CourseRegistrationWorkflow
import workflows4s.example.docs.pullrequest.PullRequestWorkflow
import workflows4s.runtime.InMemoryRuntime
import workflows4s.runtime.wakeup.NoOpKnockerUpper
import workflows4s.web.api.server.WorkflowServerEndpoints
import workflows4s.web.api.service.RealWorkflowService
import workflows4s.web.api.service.RealWorkflowService.WorkflowEntry

trait BaseServer {

  // Dummy encoders for the example workflows
  def dummyEncoder[T]: Encoder[T] = Encoder.instance { state =>
    Json.obj(
      "type" -> Json.fromString(state.getClass.getSimpleName),
      "data" -> Json.fromString(state.toString),
    )
  }

  given courseRegistrationStateEncoder: Encoder[CourseRegistrationWorkflow.CourseRegistrationState] = dummyEncoder
  given prStateEncoder: Encoder[PullRequestWorkflow.PRState] = dummyEncoder

  /**
   * Creates the API routes with CORS enabled
   */
  protected def apiRoutes: IO[HttpRoutes[IO]] = for {
    dummyRt1 <- InMemoryRuntime.default[CourseRegistrationWorkflow.Context.Ctx](
                  workflow = CourseRegistrationWorkflow.workflow,
                  initialState = CourseRegistrationWorkflow.RegistrationState.Empty,
                  knockerUpper = NoOpKnockerUpper.Agent,
                )

    dummyRt2 <- InMemoryRuntime.default[PullRequestWorkflow.Context.Ctx](
                  workflow = PullRequestWorkflow.workflow,
                  initialState = PullRequestWorkflow.PRState.Empty,
                  knockerUpper = NoOpKnockerUpper.Agent,
                )

    workflowEntries = List(
                        WorkflowEntry(
                          id = "course-registration-v1",
                          name = "Course Registration",
                          runtime = dummyRt1,
                          stateEncoder = courseRegistrationStateEncoder,
                        ),
                        WorkflowEntry(
                          id = "pull-request-v1",
                          name = "Pull Request",
                          runtime = dummyRt2,
                          stateEncoder = prStateEncoder,
                        ),
                      )

    realService     = new RealWorkflowService(workflowEntries)
    serverEndpoints = new WorkflowServerEndpoints(realService)
    routes          = Http4sServerInterpreter[IO]().toRoutes(serverEndpoints.endpoints)
  } yield CORS.policy.withAllowOriginAll(routes)
}