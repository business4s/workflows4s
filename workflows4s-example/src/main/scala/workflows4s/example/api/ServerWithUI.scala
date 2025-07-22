package workflows4s.example.api

import cats.effect.{IO, IOApp}
import cats.syntax.all.*
import com.comcast.ip4s.*
import io.circe.{Encoder, Json}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.http4s.server.middleware.CORS
import sttp.tapir.server.http4s.Http4sServerInterpreter
import workflows4s.example.courseregistration.CourseRegistrationWorkflow
import workflows4s.example.docs.pullrequest.PullRequestWorkflow
import workflows4s.runtime.InMemoryRuntime
import workflows4s.runtime.wakeup.NoOpKnockerUpper
import workflows4s.web.api.server.WorkflowServerEndpoints
import workflows4s.web.api.service.RealWorkflowService
import workflows4s.web.api.service.RealWorkflowService.WorkflowEntry

object ServerWithUI extends IOApp.Simple {

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
      dummyRt1 <- InMemoryRuntime.default[CourseRegistrationWorkflow.Context.Ctx, String](
                    workflow = CourseRegistrationWorkflow.workflow,
                    initialState = CourseRegistrationWorkflow.RegistrationState.Empty,
                    knockerUpper = NoOpKnockerUpper.Agent,
                  )

      dummyRt2 <- InMemoryRuntime.default[PullRequestWorkflow.Context.Ctx, String](
                    workflow = PullRequestWorkflow.workflow,
                    initialState = PullRequestWorkflow.PRState.Empty,
                    knockerUpper = NoOpKnockerUpper.Agent,
                  )

      workflowEntries = List(
                          WorkflowEntry(
                            id = "course-registration-v1",
                            name = "Course Registration",
                            runtime = dummyRt1,
                            parseId = identity,
                            stateEncoder = courseRegistrationStateEncoder,
                          ),
                          WorkflowEntry(
                            id = "pull-request-v1",
                            name = "Pull Request",
                            runtime = dummyRt2,
                            parseId = identity,
                            stateEncoder = prStateEncoder,
                          ),
                        )

      realService     = new RealWorkflowService(workflowEntries)
      serverEndpoints = new WorkflowServerEndpoints(realService)

      routes = Http4sServerInterpreter[IO]().toRoutes(serverEndpoints.endpoints)

      staticRoutes = {
        import org.http4s.{HttpRoutes, StaticFile}
        import org.http4s.Status.{NotFound, PermanentRedirect}
        import org.http4s.dsl.io.*
        import org.http4s.headers.Location
        HttpRoutes.of[IO] {
          case _ @GET -> Root => PermanentRedirect(Location(uri"/ui/"))

          case req @ GET -> "ui" /: tail =>
            val uiBase = "workflows4s-web-ui-bundle"
            val path   = tail.segments match {
              case Vector() => s"$uiBase/index.html"
              case segments => segments.map(_.decoded()).mkString("/")
            }

            // Try to serve file from ui-dist embedded in the classpath
            StaticFile.fromResource(s"$uiBase/$path", Some(req)).getOrElseF {
              // Fallback to index.html for client-side routing
              StaticFile.fromResource(s"$uiBase/index.html", Some(req)).getOrElseF(NotFound())
            }
        }
      }

      corsRoutes = CORS.policy.withAllowOriginAll.apply(routes <+> staticRoutes)

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
