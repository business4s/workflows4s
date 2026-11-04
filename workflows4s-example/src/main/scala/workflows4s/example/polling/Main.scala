package workflows4s.example.polling

import cats.effect.{ExitCode, IO, IOApp}
import cats.effect.unsafe.implicits.global
import workflows4s.example.pekko.DummyWithdrawalService
import workflows4s.example.withdrawal.checks.ChecksEngine
import workflows4s.example.withdrawal.{WithdrawalData, WithdrawalWorkflow}
import workflows4s.runtime.InMemorySyncRuntime
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.runtime.registry.InMemoryWorkflowRegistry
import workflows4s.runtime.wakeup.RegistryBasedWakeupPoller
import workflows4s.testing.TestClock

import scala.concurrent.duration.DurationInt

/** Example demonstrating the use of RegistryBasedWakeupPoller for automatic workflow progression.
  *
  * This approach provides:
  *   - Fast signal responses (single-step evaluation)
  *   - Automatic workflow progression via registry polling
  *
  * The workflow executes one step per signal/wakeup, and the poller continuously checks the registry for workflows in "Awaiting" status and sends
  * wakeups to progress them.
  */
object Main extends IOApp {

  override def run(args: List[String]): IO[ExitCode] = {
    val templateId: String = "withdrawal-polling"

    for {
      // Setup clock and registry
      clock    <- IO(TestClock())
      registry <- InMemoryWorkflowRegistry(clock)

      // Create engine with single-step evaluation and registry
      // Single-step means signal responses return after first step only
      engine: WorkflowInstanceEngine = WorkflowInstanceEngine.builder
                                         .withJavaTime(clock)
                                         .withoutWakeUps           // No knocker-upper needed - we use polling instead
                                         .withRegistering(registry)
                                         .withSingleStepEvaluation // Fast signal response
                                         .withLogging
                                         .get

      // Create workflow and runtime
      workflow: WithdrawalWorkflow                                 = WithdrawalWorkflow(DummyWithdrawalService, ChecksEngine)
      runtime: InMemorySyncRuntime[WithdrawalWorkflow.Context.Ctx] = new InMemorySyncRuntime[WithdrawalWorkflow.Context.Ctx](
                                                                       workflow.workflowDeclarative.provideInput(WithdrawalData.Empty),
                                                                       WithdrawalData.Empty,
                                                                       engine,
                                                                       templateId,
                                                                     )

      // Create the poller that will drive workflow progression
      poller: RegistryBasedWakeupPoller = RegistryBasedWakeupPoller.forSync[WithdrawalData](
                                            registry = registry,
                                            templateId = templateId,
                                            pollInterval = 100.millis, // Poll every 100ms
                                            lookupInstance = (id: String) => Option(runtime.instances.get(id)),
                                          )

      // Create the service that handles HTTP-like requests
      service: WithdrawalWorkflowService = WithdrawalWorkflowService.Impl(runtime)

      // Start the poller in the background
      pollerFiber <- poller.pollForever.start
      _           <- IO.println("Poller started in background")

      // Run the demo
      _ <- runDemo(service)

      // Cancel the poller (in a real app, this would run until shutdown)
      _ <- pollerFiber.cancel
      _ <- IO.println("Poller stopped")
    } yield ExitCode.Success
  }

  private def runDemo(service: WithdrawalWorkflowService): IO[Unit] = {
    import workflows4s.example.withdrawal.WithdrawalService.Iban
    import workflows4s.example.withdrawal.WithdrawalSignal.CreateWithdrawal

    for {
      _ <- IO.println("\n=== Starting Withdrawal Workflow Demo with Polling ===\n")

      // Create a withdrawal - signal returns immediately after first step
      workflowId = "withdrawal-001"
      _         <- IO.println(s"Creating withdrawal $workflowId...")
      _         <- service.startWorkflow(workflowId, CreateWithdrawal(workflowId, 100, Iban("DE89370400440532013000")))
      _         <- IO.println("Signal returned (fast response!)")

      // Check initial state
      state1 <- service.getState(workflowId)
      _      <- IO.println(s"State after signal: $state1")

      // Wait a bit for the poller to progress the workflow
      _ <- IO.println("\nWaiting for poller to progress workflow...")
      _ <- IO.sleep(500.millis)

      // Check state after polling
      state2 <- service.getState(workflowId)
      _      <- IO.println(s"State after polling: $state2")

      // Wait more for further progression
      _      <- IO.sleep(500.millis)
      state3 <- service.getState(workflowId)
      _      <- IO.println(s"Final state: $state3")

      // List all workflows
      workflows <- service.listWorkflows
      _         <- IO.println(s"\nAll workflows: ${workflows.mkString(", ")}")

      _ <- IO.println("\n=== Demo Complete ===")
    } yield ()
  }
}
