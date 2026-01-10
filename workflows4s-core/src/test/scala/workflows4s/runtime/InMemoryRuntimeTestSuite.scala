package workflows4s.runtime

import org.scalatest.freespec.AnyFreeSpecLike
import workflows4s.runtime.instanceengine.{Effect, WorkflowInstanceEngine}
import workflows4s.wio.{WCState, WIO, WorkflowContext}

/** Abstract test suite for InMemoryRuntime that can be reused with different effect types.
  */
trait InMemoryRuntimeTestSuite[F[_]] extends AnyFreeSpecLike {

  given effect: Effect[F]

  /** The workflow context type to use for tests */
  type Ctx <: WorkflowContext

  /** Create a simple workflow that produces a value */
  def simpleWorkflow: WIO.Initial[F, Ctx]

  /** The initial state for the workflow */
  def initialState: WCState[Ctx]

  protected def inMemoryRuntimeTests(): Unit = {

    "should return the same workflow instance for the same id" in {
      val runtime = effect.runSyncUnsafe(
        InMemoryRuntime.create[F, Ctx](
          workflow = simpleWorkflow,
          initialState = initialState,
          engine = WorkflowInstanceEngine.basic[F](),
        ),
      )

      val instance1 = effect.runSyncUnsafe(runtime.createInstance("id1"))
      val instance2 = effect.runSyncUnsafe(runtime.createInstance("id1"))
      assert(instance1 == instance2)
    }

    "should return different workflow instances for different ids" in {
      val runtime = effect.runSyncUnsafe(
        InMemoryRuntime.create[F, Ctx](
          workflow = simpleWorkflow,
          initialState = initialState,
          engine = WorkflowInstanceEngine.basic[F](),
        ),
      )

      val instance1 = effect.runSyncUnsafe(runtime.createInstance("id1"))
      val instance2 = effect.runSyncUnsafe(runtime.createInstance("id2"))
      assert(instance1 != instance2)
    }
  }
}
