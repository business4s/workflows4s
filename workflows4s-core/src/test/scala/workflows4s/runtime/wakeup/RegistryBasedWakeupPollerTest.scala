package workflows4s.runtime.wakeup

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.runtime.registry.InMemoryWorkflowRegistry
import workflows4s.runtime.{InMemorySyncRuntime, InMemorySyncWorkflowInstance}
import workflows4s.testing.TestClock
import workflows4s.wio.{SignalDef, StepId, TestCtx2, TestState, WIO}

import java.util.UUID
import scala.concurrent.duration.DurationInt

class RegistryBasedWakeupPollerTest extends AnyFreeSpec with Matchers {

  "RegistryBasedWakeupPoller" - {

    "should wake up workflows in Awaiting status" in {
      val clock: TestClock                   = TestClock()
      val registry: InMemoryWorkflowRegistry = InMemoryWorkflowRegistry(clock).unsafeRunSync()
      val templateId: String                 = "test-template"

      // Create engine with single-step evaluation and registry
      val engine: WorkflowInstanceEngine = WorkflowInstanceEngine.builder
        .withJavaTime(clock)
        .withoutWakeUps
        .withRegistering(registry)
        .withSingleStepEvaluation
        .withLogging
        .get

      // Create workflow: signal -> runIO -> runIO -> runIO
      val (step1Id, step1)                      = createRunIOStep("step1")
      val (step2Id, step2)                      = createRunIOStep("step2")
      val (step3Id, step3)                      = createRunIOStep("step3")
      val (signalDef, signalStepId, signalStep) = createSignalStep("signal")

      val wf: TestCtx2.WIO[TestState, Nothing, TestState] = signalStep >>> step1 >>> step2 >>> step3

      // Create runtime and instance
      val runtime: InMemorySyncRuntime[TestCtx2.Ctx] = new InMemorySyncRuntime[TestCtx2.Ctx](
        wf.provideInput(TestState.empty),
        TestState.empty,
        engine,
        templateId,
      )

      val instanceId: String                                     = UUID.randomUUID().toString
      val instance: InMemorySyncWorkflowInstance[TestCtx2.Ctx]   = runtime.createInstance(instanceId)

      // Create poller
      val poller: RegistryBasedWakeupPoller = RegistryBasedWakeupPoller.forSync[TestState](
        registry = registry,
        templateId = templateId,
        pollInterval = 10.millis,
        lookupInstance = id => Option(runtime.instances.get(id)),
      )

      // Deliver signal - with single-step, only signalStep executes
      val signalResponse: Either[workflows4s.runtime.WorkflowInstance.UnexpectedSignal, Int] =
        instance.deliverSignal(signalDef, 42)

      signalResponse.isRight shouldBe true

      // After signal, only signalStep should have executed
      instance.queryState().executed shouldBe List(signalStepId)

      // Poll once - should execute step1
      poller.pollOnce.unsafeRunSync()
      instance.queryState().executed shouldBe List(signalStepId, step1Id)

      // Poll again - should execute step2
      poller.pollOnce.unsafeRunSync()
      instance.queryState().executed shouldBe List(signalStepId, step1Id, step2Id)

      // Poll again - should execute step3
      poller.pollOnce.unsafeRunSync()
      instance.queryState().executed shouldBe List(signalStepId, step1Id, step2Id, step3Id)

      // Poll again - workflow is finished, nothing should change
      poller.pollOnce.unsafeRunSync()
      instance.queryState().executed shouldBe List(signalStepId, step1Id, step2Id, step3Id)
    }

    "should handle multiple workflows" in {
      val clock: TestClock                   = TestClock()
      val registry: InMemoryWorkflowRegistry = InMemoryWorkflowRegistry(clock).unsafeRunSync()
      val templateId: String                 = "test-template"

      val engine: WorkflowInstanceEngine = WorkflowInstanceEngine.builder
        .withJavaTime(clock)
        .withoutWakeUps
        .withRegistering(registry)
        .withSingleStepEvaluation
        .withLogging
        .get

      val (step1Id, step1)                      = createRunIOStep("step1")
      val (signalDef, signalStepId, signalStep) = createSignalStep("signal")

      val wf: TestCtx2.WIO[TestState, Nothing, TestState] = signalStep >>> step1

      val runtime: InMemorySyncRuntime[TestCtx2.Ctx] = new InMemorySyncRuntime[TestCtx2.Ctx](
        wf.provideInput(TestState.empty),
        TestState.empty,
        engine,
        templateId,
      )

      val instance1: InMemorySyncWorkflowInstance[TestCtx2.Ctx] = runtime.createInstance("instance1")
      val instance2: InMemorySyncWorkflowInstance[TestCtx2.Ctx] = runtime.createInstance("instance2")

      val poller: RegistryBasedWakeupPoller = RegistryBasedWakeupPoller.forSync[TestState](
        registry = registry,
        templateId = templateId,
        pollInterval = 10.millis,
        lookupInstance = id => Option(runtime.instances.get(id)),
      )

      // Deliver signals to both instances
      instance1.deliverSignal(signalDef, 1)
      instance2.deliverSignal(signalDef, 2)

      // Both should have only signalStep executed
      instance1.queryState().executed shouldBe List(signalStepId)
      instance2.queryState().executed shouldBe List(signalStepId)

      // Poll once - should wake up both
      val wokenUp: Int = poller.pollOnce.unsafeRunSync()
      wokenUp shouldBe 2

      // Both should now have step1 executed
      instance1.queryState().executed shouldBe List(signalStepId, step1Id)
      instance2.queryState().executed shouldBe List(signalStepId, step1Id)
    }

    "should stop at next signal" in {
      val clock: TestClock                   = TestClock()
      val registry: InMemoryWorkflowRegistry = InMemoryWorkflowRegistry(clock).unsafeRunSync()
      val templateId: String                 = "test-template"

      val engine: WorkflowInstanceEngine = WorkflowInstanceEngine.builder
        .withJavaTime(clock)
        .withoutWakeUps
        .withRegistering(registry)
        .withSingleStepEvaluation
        .withLogging
        .get

      val (step1Id, step1)                         = createRunIOStep("step1")
      val (signalDef1, signalStepId1, signalStep1) = createSignalStep("signal1")
      val (signalDef2, signalStepId2, signalStep2) = createSignalStep("signal2")
      val (step2Id, step2)                         = createRunIOStep("step2")

      val wf: TestCtx2.WIO[TestState, Nothing, TestState] = signalStep1 >>> step1 >>> signalStep2 >>> step2

      val runtime: InMemorySyncRuntime[TestCtx2.Ctx] = new InMemorySyncRuntime[TestCtx2.Ctx](
        wf.provideInput(TestState.empty),
        TestState.empty,
        engine,
        templateId,
      )

      val instance: InMemorySyncWorkflowInstance[TestCtx2.Ctx] = runtime.createInstance("instance1")

      val poller: RegistryBasedWakeupPoller = RegistryBasedWakeupPoller.forSync[TestState](
        registry = registry,
        templateId = templateId,
        pollInterval = 10.millis,
        lookupInstance = id => Option(runtime.instances.get(id)),
      )

      // Deliver first signal
      instance.deliverSignal(signalDef1, 1)
      instance.queryState().executed shouldBe List(signalStepId1)

      // Poll - should execute step1, then stop at signalStep2
      poller.pollOnce.unsafeRunSync()
      instance.queryState().executed shouldBe List(signalStepId1, step1Id)

      // Poll again - workflow is waiting for signal, nothing should change
      poller.pollOnce.unsafeRunSync()
      instance.queryState().executed shouldBe List(signalStepId1, step1Id)

      // Deliver second signal
      instance.deliverSignal(signalDef2, 2)
      instance.queryState().executed shouldBe List(signalStepId1, step1Id, signalStepId2)

      // Poll - should execute step2
      poller.pollOnce.unsafeRunSync()
      instance.queryState().executed shouldBe List(signalStepId1, step1Id, signalStepId2, step2Id)
    }
  }

  private def createRunIOStep(name: String): (StepId, TestCtx2.WIO[TestState, Nothing, TestState]) = {
    import TestCtx2.WIO
    case class RunIODone(stepId: StepId) extends TestCtx2.Event
    val stepId: StepId = StepId.random(name)
    val wio: TestCtx2.WIO[TestState, Nothing, TestState] = WIO
      .runIO[TestState](_ => IO.pure(RunIODone(stepId)))
      .handleEvent((st, evt) => st.addExecuted(evt.stepId))
      .done
    (stepId, wio)
  }

  private def createSignalStep(name: String): (SignalDef[Int, Int], StepId, WIO.IHandleSignal[TestState, Nothing, TestState, TestCtx2.Ctx]) = {
    val signalDef: SignalDef[Int, Int] = SignalDef[Int, Int](id = UUID.randomUUID().toString)
    class SigEvent(val req: Int) extends TestCtx2.Event with Serializable {
      override def toString: String = s"SigEvent($req)"
    }
    val stepId: StepId = StepId.random(name)
    val wio: WIO.IHandleSignal[TestState, Nothing, TestState, TestCtx2.Ctx] = TestCtx2.WIO
      .handleSignal(signalDef)
      .using[TestState]
      .purely((_, req) => new SigEvent(req))
      .handleEvent((st, _) => st.addExecuted(stepId))
      .produceResponse((_, evt) => evt.req)
      .done
    (signalDef, stepId, wio)
  }
}
