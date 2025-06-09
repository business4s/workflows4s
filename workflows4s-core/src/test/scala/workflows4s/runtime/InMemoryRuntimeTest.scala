package workflows4s.runtime

import org.scalatest.freespec.AnyFreeSpec
import workflows4s.runtime.wakeup.NoOpKnockerUpper
import cats.effect.unsafe.implicits.global

class InMemoryRuntimeTest extends AnyFreeSpec {

  import workflows4s.wio.TestCtx.*

  "InMemoryRuntime" - {

    "should return the same workflow instance for the same id" in {
      val workflow: WIO.Initial = WIO.pure("myValue").done
      val runtime               = InMemoryRuntime
        .default[Ctx, String](
          workflow = workflow,
          initialState = "initialState",
          knockerUpper = NoOpKnockerUpper.Agent,
        )
        .unsafeRunSync()

      val instance1 = runtime.createInstance("id1").unsafeRunSync()
      val instance2 = runtime.createInstance("id1").unsafeRunSync()
      assert(instance1 == instance2)
    }

  }

}
