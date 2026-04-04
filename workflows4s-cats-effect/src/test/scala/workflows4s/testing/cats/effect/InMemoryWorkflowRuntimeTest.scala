package workflows4s.testing.cats.effect

import workflows4s.testing.WorkflowRuntimeTest
import workflows4s.wio.TestCtx2

class InMemoryConcurrentWorkflowRuntimeTest extends WorkflowRuntimeTest.Suite {

  "in-memory-concurrent" - {
    workflowTests(InMemoryConcurrentTestRuntimeAdapter[TestCtx2.Ctx]())
  }

}
