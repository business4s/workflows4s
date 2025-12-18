package workflows4s.runtime

import cats.Id
import org.scalatest.freespec.AnyFreeSpec
import workflows4s.runtime.instanceengine.{Effect, WorkflowInstanceEngine}

class InMemorySyncRuntimeTest extends AnyFreeSpec {

  import workflows4s.wio.TestCtx.*

  "InMemoryRuntime with Id effect" - {

    "should return the same workflow instance for the same id" in {
      given Effect[Id]          = effect
      val workflow: WIO.Initial = WIO.pure("myValue").done
      val runtime               = InMemoryRuntime.create[Id, Ctx](
        workflow = workflow,
        initialState = "initialState",
        engine = WorkflowInstanceEngine.basic[Id](),
      )

      val instance1 = runtime.createInstance("id1")
      val instance2 = runtime.createInstance("id1")
      assert(instance1 == instance2)
    }

  }

}
