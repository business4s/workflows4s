package workflows4s.example.api

import cats.effect.IO
import io.circe.Encoder
import org.http4s.HttpRoutes
import org.http4s.server.middleware.CORS
import sttp.tapir.server.http4s.Http4sServerInterpreter
import workflows4s.example.courseregistration.CourseRegistrationWorkflow
import workflows4s.example.docs.pullrequest.PullRequestWorkflow
import workflows4s.example.docs.pullrequest.PullRequestWorkflow.PRState
import workflows4s.runtime.InMemoryRuntime
import workflows4s.runtime.wakeup.NoOpKnockerUpper
import workflows4s.web.api.server.RealWorkflowService.{SignalSupport, WorkflowEntry}
import workflows4s.web.api.server.WorkflowServerEndpoints

trait BaseServer {

  /** Creates the API routes with CORS enabled
    */
  protected def apiRoutes: IO[HttpRoutes[IO]] = for {
    courseRegRuntime <- InMemoryRuntime.default[CourseRegistrationWorkflow.Context.Ctx](
                  workflow = CourseRegistrationWorkflow.workflow,
                  initialState = CourseRegistrationWorkflow.RegistrationState.Empty,
                  knockerUpper = NoOpKnockerUpper.Agent,
                )

    pullReqRuntime <- InMemoryRuntime.default[PullRequestWorkflow.Context.Ctx](
                  workflow = PullRequestWorkflow.workflow,
                  initialState = PullRequestWorkflow.PRState.Empty,
                  knockerUpper = NoOpKnockerUpper.Agent,
                )

    workflowEntries = List(
                        WorkflowEntry(
                          id = "course-registration-v1",
                          name = "Course Registration",
                          runtime = courseRegRuntime,
                          stateEncoder = summon[Encoder[CourseRegistrationWorkflow.CourseRegistrationState]],
                          signalSupport = SignalSupport.builder
                            .add(CourseRegistrationWorkflow.Signals.startBrowsing)
                            .add(CourseRegistrationWorkflow.Signals.setPriorities)
                            .build,
                        ),
                        WorkflowEntry(
                          id = "pull-request-v1",
                          name = "Pull Request",
                          runtime = pullReqRuntime,
                          stateEncoder = summon[Encoder[PRState]],
                          signalSupport = SignalSupport.builder
                            .add(PullRequestWorkflow.Signals.createPR)
                            .add(PullRequestWorkflow.Signals.reviewPR)
                            .build,
                        ),
                      )
    routes          = Http4sServerInterpreter[IO]().toRoutes(WorkflowServerEndpoints.get[IO](workflowEntries))
  } yield CORS.policy.withAllowOriginAll(routes)
}
