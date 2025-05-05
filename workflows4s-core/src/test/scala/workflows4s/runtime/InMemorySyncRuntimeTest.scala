package workflows4s.runtime

import org.scalatest.freespec.AnyFreeSpec
import workflows4s.runtime.wakeup.NoOpKnockerUpper

class InMemorySyncRuntimeTest extends AnyFreeSpec {

  import workflows4s.wio.TestCtx.*

  "InMemorySyncRuntime" - {

    "should return the same workflow instance for the same id" in {
      val workflow: WIO.Initial = WIO.pure("myValue").done
      val runtime = InMemorySyncRuntime
        .default[Ctx, String](
          workflow = workflow,
          initialState = "initialState",
          knockerUpperAgent = NoOpKnockerUpper.Agent,
        )

      val instance1 = runtime.createInstance("id1")
      val instance2 = runtime.createInstance("id1")
      assert(instance1 == instance2)
    }

  }

}
