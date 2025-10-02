package workflows4s.example.api

import cats.effect.IO
import io.circe.Encoder
import org.http4s.HttpRoutes
import org.http4s.server.middleware.CORS
import sttp.tapir.server.http4s.Http4sServerInterpreter
import workflows4s.example.courseregistration.CourseRegistrationWorkflow
import workflows4s.example.docs.pullrequest.PullRequestWorkflow
import workflows4s.example.docs.pullrequest.PullRequestWorkflow.PRState
import workflows4s.example.pekko.DummyWithdrawalService
import workflows4s.example.withdrawal.{WithdrawalData, WithdrawalWorkflow}
import workflows4s.example.withdrawal.checks.ChecksEngine
import workflows4s.runtime.InMemoryRuntime
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.runtime.registry.InMemoryWorkflowRegistry
import workflows4s.runtime.wakeup.SleepingKnockerUpper
import workflows4s.web.api.server.RealWorkflowService.{SignalSupport, WorkflowEntry}
import workflows4s.web.api.server.WorkflowServerEndpoints

trait BaseServer {


  /** Creates the API routes with CORS enabled
    */
  protected def apiRoutes: IO[HttpRoutes[IO]] = {

    for {
      (knockerUpper, release) <- SleepingKnockerUpper.create().allocated // TODO, leaking resources
      registry                <- InMemoryWorkflowRegistry()
      engine                   = WorkflowInstanceEngine.default(knockerUpper, registry)
      courseRegRuntime        <- InMemoryRuntime.default[CourseRegistrationWorkflow.Context.Ctx](
                                   workflow = CourseRegistrationWorkflow.workflow,
                                   initialState = CourseRegistrationWorkflow.RegistrationState.Empty,
                                   engine = engine,
                                 )

      pullReqRuntime <- InMemoryRuntime.default[PullRequestWorkflow.Context.Ctx](
                          workflow = PullRequestWorkflow.workflow,
                          initialState = PullRequestWorkflow.PRState.Empty,
                          engine = engine,
                        )

      withdrawalWf       = WithdrawalWorkflow(DummyWithdrawalService, ChecksEngine)
      withdrawalRuntime <- InMemoryRuntime.default[WithdrawalWorkflow.Context.Ctx](
                             workflow = withdrawalWf.workflowDeclarative,
                             initialState = WithdrawalData.Empty,
                             engine = engine,
                           )
      workflowEntries = List[WorkflowEntry[IO, ?]](
                          WorkflowEntry(
                            name = "Course Registration",
                            runtime = courseRegRuntime,
                            stateEncoder = summon[Encoder[CourseRegistrationWorkflow.CourseRegistrationState]],
                            signalSupport = SignalSupport.builder
                              .add(CourseRegistrationWorkflow.Signals.startBrowsing)
                              .add(CourseRegistrationWorkflow.Signals.setPriorities)
                              .build,
                          ),
                          WorkflowEntry(
                            name = "Pull Request",
                            runtime = pullReqRuntime,
                            stateEncoder = summon[Encoder[PRState]],
                            signalSupport = SignalSupport.builder
                              .add(PullRequestWorkflow.Signals.createPR)
                              .add(PullRequestWorkflow.Signals.reviewPR)
                              .build,
                          ),
                          WorkflowEntry(
                            name = "Withdrawal",
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
      routes          = Http4sServerInterpreter[IO]().toRoutes(WorkflowServerEndpoints.get[IO](workflowEntries, registry))
    } yield CORS.policy.withAllowOriginAll(routes)
  }
}
