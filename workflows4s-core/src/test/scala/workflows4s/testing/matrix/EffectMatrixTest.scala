package workflows4s.testing.matrix

import org.scalatest.freespec.AnyFreeSpecLike
import workflows4s.testing.TestRuntimeAdapter
import workflows4s.wio.*

trait EffectMatrixTest extends AnyFreeSpecLike {

  // tests defined to support verifying behavior across different effect types
  def matrixTests(ctx: TestContextBase)(
      mkAdapter: => TestRuntimeAdapter[?, ctx.Ctx],
  ): Unit = {
    "Workflow 1: pure >>> runIO >>> pure" in {
      val adapter      = mkAdapter
      val (id1, step1) = TestWorkflows.pure(ctx)
      val (id2, step2) = TestWorkflows.runIO(ctx)
      val (id3, step3) = TestWorkflows.pure(ctx)
      val workflow     = step1 >>> step2 >>> step3
      val actor        = adapter.runWorkflow(workflow.provideInput(TestState.empty), TestState.empty)
      actor.wakeup()
      assert(actor.queryState().executed == List(id1, id2, id3))
    }

    "Workflow 2: signal >>> timer" in {
      val adapter                  = mkAdapter
      val (sigDef, sigId, sigStep) = TestWorkflows.signal(ctx)
      val (duration, timerStep)    = TestWorkflows.timer(ctx)()
      val workflow                 = sigStep >>> timerStep
      val actor                    = adapter.runWorkflow(workflow.provideInput(TestState.empty), TestState.empty)

      assert(actor.queryState().executed.isEmpty)

      val resp = actor.deliverSignal(sigDef, 42)
      assert(resp == Right(42))
      assert(actor.queryState().executed == List(sigId))

      adapter.clock.advanceBy(duration)
      adapter.executeDueWakup(actor)
      assert(actor.queryState().executed == List(sigId))
    }

    "recovery replays correctly" in {
      val adapter      = mkAdapter
      val (id1, step1) = TestWorkflows.pure(ctx)
      val (id2, step2) = TestWorkflows.runIO(ctx)
      val workflow     = step1 >>> step2
      val actor        = adapter.runWorkflow(workflow.provideInput(TestState.empty), TestState.empty)
      actor.wakeup()
      assert(actor.queryState().executed == List(id1, id2))

      val recovered = adapter.recover(actor)
      assert(recovered.queryState().executed == List(id1, id2))
    }
  }
}
