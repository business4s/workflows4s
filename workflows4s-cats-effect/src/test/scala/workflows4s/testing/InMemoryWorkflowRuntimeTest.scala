package workflows4s.testing

import workflows4s.wio.TestCtx2

class InMemoryConcurrentWorkflowRuntimeTest extends WorkflowRuntimeTest.Suite {

  "in-memory-concurrent" - {
    workflowTests(InMemoryConcurrentTestRuntimeAdapter[TestCtx2.Ctx]())
  }

}
