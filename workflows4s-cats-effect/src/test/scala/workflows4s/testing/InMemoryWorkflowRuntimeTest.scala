package workflows4s.testing

import workflows4s.wio.TestCtx2

class InMemoryWorkflowRuntimeTest extends WorkflowRuntimeTest.Suite {

  "in-memory" - {
    workflowTests(InMemoryTestRuntimeAdapter[TestCtx2.Ctx]())
  }

}
