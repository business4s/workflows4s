package workflows4s.testing

import workflows4s.wio.TestCtx2

class InMemorySynchronizedWorkflowRuntimeTest extends WorkflowRuntimeTest.Suite {

  "in-memory-synchronized" - {
    workflowTests(TestRuntimeAdapter.InMemorySync[TestCtx2.Ctx]())
  }

}
