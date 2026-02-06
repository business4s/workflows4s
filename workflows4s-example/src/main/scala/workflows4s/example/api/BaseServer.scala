package workflows4s.example.api

import cats.effect.IO
import cats.effect.kernel.Resource
import cats.implicits.catsSyntaxOptionId
import cats.syntax.all.*
import com.comcast.ip4s.*
import io.circe.Encoder
import org.http4s.HttpRoutes
import org.http4s.dsl.io.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.http4s.server.middleware.CORS
import sttp.tapir.server.http4s.Http4sServerInterpreter
import workflows4s.example.courseregistration.CourseRegistrationWorkflow
import workflows4s.example.docs.pullrequest.PullRequestWorkflow
import workflows4s.example.docs.pullrequest.PullRequestWorkflow.PRState
import workflows4s.example.pekko.DummyWithdrawalService
import workflows4s.example.withdrawal.checks.ChecksEngine
import workflows4s.example.withdrawal.{WithdrawalData, WithdrawalWorkflow}
import workflows4s.runtime.InMemoryRuntime
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.runtime.registry.InMemoryWorkflowRegistry
import workflows4s.runtime.wakeup.SleepingKnockerUpper
import workflows4s.ui.bundle.UiEndpoints
import workflows4s.web.api.server.{SignalSupport, WorkflowEntry, WorkflowServerEndpoints}
import workflows4s.web.api.model.UIConfig

trait BaseServer {

  /** Creates the API routes with CORS enabled
    */
  protected def apiRoutes: Resource[IO, HttpRoutes[IO]] = {
    for {
      knockerUpper     <- SleepingKnockerUpper.create()
      registry         <- InMemoryWorkflowRegistry().toResource
      engine            = WorkflowInstanceEngine.default(knockerUpper, registry)
      courseRegRuntime <- InMemoryRuntime
                            .default[CourseRegistrationWorkflow.Context.Ctx](
                              workflow = CourseRegistrationWorkflow.workflow,
                              initialState = CourseRegistrationWorkflow.RegistrationState.Empty,
                              engine = engine,
                            )
                            .toResource

      pullReqRuntime <- InMemoryRuntime
                          .default[PullRequestWorkflow.Context.Ctx](
                            workflow = PullRequestWorkflow.workflow,
                            initialState = PullRequestWorkflow.PRState.Empty,
                            engine = engine,
                          )
                          .toResource

      withdrawalWf       = WithdrawalWorkflow(DummyWithdrawalService, ChecksEngine)
      withdrawalRuntime <- InMemoryRuntime
                             .default[WithdrawalWorkflow.Context.Ctx](
                               workflow = withdrawalWf.workflowDeclarative,
                               initialState = WithdrawalData.Empty,
                               engine = engine,
                             )
                             .toResource
      workflowEntries    = List[WorkflowEntry[IO, ?]](
                             WorkflowEntry(
                               name = "Course Registration",
                               description =
                                 Some("Example workflow demonstrating a simple course registration process with browsing and setting priorities."),
                               runtime = courseRegRuntime,
                               stateEncoder = summon[Encoder[CourseRegistrationWorkflow.CourseRegistrationState]],
                               signalSupport = SignalSupport.builder
                                 .add(CourseRegistrationWorkflow.Signals.startBrowsing)
                                 .add(CourseRegistrationWorkflow.Signals.setPriorities)
                                 .build,
                             ),
                             WorkflowEntry(
                               name = "Pull Request",
                               description = Some("Example workflow showcasing a pull request lifecycle: creation and review."),
                               runtime = pullReqRuntime,
                               stateEncoder = summon[Encoder[PRState]],
                               signalSupport = SignalSupport.builder
                                 .add(PullRequestWorkflow.Signals.createPR)
                                 .add(PullRequestWorkflow.Signals.reviewPR)
                                 .build,
                             ),
                             WorkflowEntry(
                               name = "Withdrawal",
                               description = Some("Example withdrawal workflow with a checks engine and cancellation path."),
                               runtime = withdrawalRuntime,
                               stateEncoder = summon[Encoder[WithdrawalData]],
                               signalSupport = SignalSupport.builder
                                 .add(WithdrawalWorkflow.Signals.createWithdrawal)
                                 .add(WithdrawalWorkflow.Signals.executionCompleted)
                                 .add(WithdrawalWorkflow.Signals.cancel)
                                 .add(ChecksEngine.Signals.review)
                                 .build,
                             ),
                           )
      routes             = Http4sServerInterpreter[IO]().toRoutes(WorkflowServerEndpoints.get[IO](workflowEntries, registry.some))
    } yield CORS.policy.withAllowOriginAll(routes)
  }

  protected def serverWithUi(port: Int, apiUrl: String): Resource[IO, org.http4s.server.Server] = {
    for {
      api      <- apiRoutes
      uiRoutes  = Http4sServerInterpreter[IO]().toRoutes(UiEndpoints.get(UIConfig(sttp.model.Uri.unsafeParse(apiUrl), true)))
      redirect  = org.http4s.HttpRoutes.of[IO] {
                    case req @ org.http4s.Method.GET -> Root / "ui" =>
                      org.http4s
                        .Response[IO](org.http4s.Status.PermanentRedirect)
                        .putHeaders(org.http4s.headers.Location(req.uri / ""))
                        .pure[IO]
                    case org.http4s.Method.GET -> Root              =>
                      org.http4s
                        .Response[IO](org.http4s.Status.PermanentRedirect)
                        .putHeaders(org.http4s.headers.Location(org.http4s.Uri.unsafeFromString("/ui/")))
                        .pure[IO]
                  }
      allRoutes = api <+> redirect <+> uiRoutes
      server   <- EmberServerBuilder
                    .default[IO]
                    .withHost(ipv4"0.0.0.0")
                    .withPort(Port.fromInt(port).get)
                    .withHttpApp(allRoutes.orNotFound)
                    .build
    } yield server
  }
}
