package workflows4s.testing

import cats.effect.IO
import workflows4s.cats.CatsEffect
import workflows4s.runtime.instanceengine.Effect

class IOWorkflowTest extends WorkflowRuntimeTest[IO] {

  override given effect: Effect[IO] = CatsEffect.ioEffect

  override def unsafeRun(program: => IO[Unit]): Unit =
    effect.runSyncUnsafe(program)

  def getAdapter: Adapter = new WorkflowTestAdapter.InMemory[IO, ctx.type]()

  workflowTests(getAdapter)
}
