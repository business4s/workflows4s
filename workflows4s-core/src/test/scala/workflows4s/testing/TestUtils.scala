package workflows4s.testing

import workflows4s.runtime.wakeup.NoOpKnockerUpper
import workflows4s.runtime.{InMemorySyncRuntime, InMemorySyncWorkflowInstance}
import workflows4s.wio.{TestCtx, *}

object TestUtils {

  def createInstance(wio: WIO.Initial[TestCtx.Ctx]): (TestClock, InMemorySyncWorkflowInstance[TestCtx.Ctx]) = {
    val clock                                               = new TestClock()
    import cats.effect.unsafe.implicits.global
    val instance: InMemorySyncWorkflowInstance[TestCtx.Ctx] =
      new InMemorySyncRuntime(wio, "initialState", clock, NoOpKnockerUpper.Agent).createInstance(())
    (clock, instance)
  }

}
