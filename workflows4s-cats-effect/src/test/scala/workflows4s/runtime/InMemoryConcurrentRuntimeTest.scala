package workflows4s.runtime

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.freespec.AnyFreeSpec
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine

class InMemoryConcurrentRuntimeTest extends AnyFreeSpec {

  import workflows4s.wio.TestCtx.*

  "InMemoryConcurrentRuntime" - {

    "should return the same workflow instance for the same id" in {
      val workflow: WIO.Initial = WIO.pure("myValue").done
      val runtime               = InMemoryConcurrentRuntime
        .default[IO, Ctx](
          workflow = workflow,
          initialState = "initialState",
          engine = WorkflowInstanceEngine.basic[IO](),
        )
        .unsafeRunSync()

      val instance1 = runtime.createInstance("id1").unsafeRunSync()
      val instance2 = runtime.createInstance("id1").unsafeRunSync()
      assert(instance1 == instance2)
    }

  }

}
