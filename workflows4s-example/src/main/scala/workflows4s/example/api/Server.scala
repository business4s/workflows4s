package workflows4s.example.api

import cats.effect.{IO, IOApp}
import com.comcast.ip4s.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.middleware.CORS
import org.http4s.implicits.*
import sttp.tapir.server.http4s.Http4sServerInterpreter
import workflows4s.web.api.server.WorkflowServerEndpoints
import workflows4s.web.api.service.RealWorkflowService
import workflows4s.web.api.service.RealWorkflowService.WorkflowEntry
import workflows4s.runtime.wakeup.NoOpKnockerUpper
import workflows4s.runtime.InMemoryRuntime
import workflows4s.example.courseregistration.CourseRegistrationWorkflow
import workflows4s.example.docs.pullrequest.PullRequestWorkflow
import io.circe.{Encoder, Json}

object Server extends IOApp.Simple {

  def dummyEncoder[T]: Encoder[T] = Encoder.instance { state =>
    Json.obj(
      "type" -> Json.fromString(state.getClass.getSimpleName),
      "data" -> Json.fromString(state.toString),
    )
  }

  given courseRegistrationStateEncoder: Encoder[CourseRegistrationWorkflow.CourseRegistrationState] = dummyEncoder
  given prStateEncoder: Encoder[PullRequestWorkflow.PRState]                                        = dummyEncoder

  def run: IO[Unit] = {
    for {
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

      routes = Http4sServerInterpreter[IO]().toRoutes(serverEndpoints.endpoints)

      corsRoutes = CORS.policy.withAllowOriginAll.apply(routes)

      _ <- EmberServerBuilder
             .default[IO]
             .withHost(ipv4"0.0.0.0")
             .withPort(port"8081")
             .withHttpApp(corsRoutes.orNotFound)
             .build
             .use { server =>
               IO.println(s"Real Workflows4s API Server running at http://${server.address}") *>
                 IO.never
             }
    } yield ()
  }
}
