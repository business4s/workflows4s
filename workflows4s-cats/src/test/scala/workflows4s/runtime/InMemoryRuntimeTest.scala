package workflows4s.runtime

import cats.effect.IO
import org.scalatest.freespec.AnyFreeSpec
import cats.effect.unsafe.implicits.global
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine

class InMemoryRuntimeTest extends AnyFreeSpec {

  import workflows4s.wio.IOTestCtx.{*, given}

  "InMemoryRuntime" - {

    "should return the same workflow instance for the same id" in {
      val workflow: WIO.Initial = WIO.pure("myValue").done
      val runtime               = InMemoryRuntime.create[IO, Ctx](
        workflow = workflow,
        initialState = "initialState",
        engine = WorkflowInstanceEngine.basic[IO](),
      )

      val instance1 = runtime.createInstance("id1").unsafeRunSync()
      val instance2 = runtime.createInstance("id1").unsafeRunSync()
      assert(instance1 == instance2)
    }

  }

}
