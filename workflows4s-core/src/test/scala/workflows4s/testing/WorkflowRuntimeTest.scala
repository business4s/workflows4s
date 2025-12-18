package workflows4s.testing

import org.scalatest.freespec.AnyFreeSpec
import workflows4s.wio.IOTestCtx2

/** Tests for workflow runtime concurrency semantics. Uses IO-based infrastructure for proper fiber semantics.
  */
class WorkflowRuntimeTest extends AnyFreeSpec with IOWorkflowRuntimeTest.Suite {

  "in-memory" - {
    ioWorkflowTests(IOTestRuntimeAdapter.InMemory[IOTestCtx2.Ctx]())
  }

}
