package workflows4s.example.api

import cats.effect.IO
import cats.effect.kernel.Resource
import cats.implicits.catsSyntaxOptionId
import io.circe.Encoder
import org.http4s.HttpRoutes
import org.http4s.server.middleware.CORS
import sttp.tapir.server.http4s.Http4sServerInterpreter
import workflows4s.cats.CatsEffect.given
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
import workflows4s.web.api.server.{SignalSupport, WorkflowEntry, WorkflowServerEndpoints}

trait BaseServer {

  /** Creates the API routes with CORS enabled
    */
  protected def apiRoutes: Resource[IO, HttpRoutes[IO]] = {
    for {
      knockerUpper    <- SleepingKnockerUpper.create[IO].toResource
      registry        <- InMemoryWorkflowRegistry[IO]().toResource
      engine           = WorkflowInstanceEngine.default[IO](knockerUpper, registry)
      courseRegRuntime = InMemoryRuntime
                           .create[IO, CourseRegistrationWorkflow.Context.Ctx](
                             workflow = CourseRegistrationWorkflow.workflow,
                             initialState = CourseRegistrationWorkflow.RegistrationState.Empty,
                             engine = engine,
                           )

      pullReqRuntime = InMemoryRuntime
                         .create[IO, PullRequestWorkflow.Context.Ctx](
                           workflow = PullRequestWorkflow.workflow,
                           initialState = PullRequestWorkflow.PRState.Empty,
                           engine = engine,
                         )

      withdrawalWf      = WithdrawalWorkflow(DummyWithdrawalService, ChecksEngine)
      withdrawalRuntime = InMemoryRuntime
                            .create[IO, WithdrawalWorkflow.Context.Ctx](
                              workflow = withdrawalWf.workflowDeclarative,
                              initialState = WithdrawalData.Empty,
                              engine = engine,
                            )
      workflowEntries   = List[WorkflowEntry[IO, ?]](
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
      routes            = Http4sServerInterpreter[IO]().toRoutes(WorkflowServerEndpoints.get[IO](workflowEntries, registry.some))
    } yield CORS.policy.withAllowOriginAll(routes)
  }
}
