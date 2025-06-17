 package workflows4s.web.api

import cats.effect.{IO, IOApp}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.cors.scaladsl.CorsDirectives.*
import sttp.tapir.server.pekkohttp.PekkoHttpServerInterpreter
import workflows4s.web.api.server.WorkflowServerEndpoints
import workflows4s.web.api.service.RealWorkflowService  
import workflows4s.web.api.service.RealWorkflowService.WorkflowEntry
import workflows4s.runtime.wakeup.NoOpKnockerUpper
import workflows4s.runtime.pekko.PekkoRuntime
import workflows4s.runtime.WorkflowRuntime
import workflows4s.example.courseregistration.CourseRegistrationWorkflow
import workflows4s.example.docs.pullrequest.PullRequestWorkflow
import io.circe.{Encoder, Json}
import scala.concurrent.ExecutionContext

object Main extends IOApp.Simple {

  given courseRegistrationStateEncoder: Encoder[CourseRegistrationWorkflow.CourseRegistrationState] =
    Encoder.instance { state =>
      Json.obj(
        "type" -> Json.fromString(state.getClass.getSimpleName),
        "data" -> Json.fromString(state.toString)  
      )
    }

  given prStateEncoder: Encoder[PullRequestWorkflow.PRState] =
    Encoder.instance { state =>
      Json.obj(
        "type" -> Json.fromString(state.getClass.getSimpleName),
        "data" -> Json.fromString(state.toString)  
      )
    }

  def run: IO[Unit] = {
    given system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "workflows4s-api")
    given ec: ExecutionContext = system.executionContext

    val dummyRt1_pekko = PekkoRuntime.create[CourseRegistrationWorkflow.Context.type](
      entityName = "course-registration",
      workflow = CourseRegistrationWorkflow.workflow.asInstanceOf[workflows4s.wio.WIO.Initial[CourseRegistrationWorkflow.Context.type]],
      initialState = CourseRegistrationWorkflow.RegistrationState.Empty.asInstanceOf[workflows4s.wio.WCState[CourseRegistrationWorkflow.Context.type]],
      knockerUpper = NoOpKnockerUpper.Agent
    )
    val dummyRt1: WorkflowRuntime[IO, CourseRegistrationWorkflow.Context.type, String] =
      dummyRt1_pekko.asInstanceOf[WorkflowRuntime[IO, CourseRegistrationWorkflow.Context.type, String]]

    val prInitialState: PullRequestWorkflow.Context.State = PullRequestWorkflow.PRState.Empty

    val dummyRt2_pekko = PekkoRuntime.create[PullRequestWorkflow.Context.type](
      entityName = "pull-request",
      workflow = PullRequestWorkflow.workflow.asInstanceOf[workflows4s.wio.WIO.Initial[PullRequestWorkflow.Context.type]],
      initialState = prInitialState.asInstanceOf[workflows4s.wio.WCState[PullRequestWorkflow.Context.type]],
      knockerUpper = NoOpKnockerUpper.Agent
    )
    val dummyRt2: WorkflowRuntime[IO, PullRequestWorkflow.Context.type, String] =
      dummyRt2_pekko.asInstanceOf[WorkflowRuntime[IO, PullRequestWorkflow.Context.type, String]]

    dummyRt1_pekko.initializeShard()
    dummyRt2_pekko.initializeShard()

    val workflowEntries = List(
      WorkflowEntry(
        id = "course-registration-v1",
        name = "Course Registration",
        runtime = dummyRt1,
        parseId = identity,
        stateEncoder = courseRegistrationStateEncoder.asInstanceOf[Encoder[workflows4s.wio.WCState[CourseRegistrationWorkflow.Context.type]]]
      ),
      WorkflowEntry(
        id = "pull-request-v1",
        name = "Pull Request",
        runtime = dummyRt2,
        parseId = identity,
        stateEncoder = prStateEncoder.asInstanceOf[Encoder[workflows4s.wio.WCState[PullRequestWorkflow.Context.type]]]
      )
    )

    val realService = new RealWorkflowService(workflowEntries)
    val serverEndpoints = new WorkflowServerEndpoints(realService)

    val routes = PekkoHttpServerInterpreter().toRoute(serverEndpoints.endpoints)
    val corsRoutes = cors() {
      routes
    }

    for {
      binding <- IO.fromFuture(IO {
        Http()
          .newServerAt("localhost", 8081)
          .bind(corsRoutes)
      })
      _ <- IO(println(s"Real Workflows4s API Server running at http://${binding.localAddress.getHostString}:${binding.localAddress.getPort}")) // Use getHostString
      _ <- IO.never
    } yield ()
  }
}