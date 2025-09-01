package workflows4s.example.docs

object DraftParallelExample {
  import workflows4s.wio.DraftWorkflowContext._

  // start_draft
  // Create a simple parallel workflow
  val stepA = WIO.draft.step("Task A")
  val stepB = WIO.draft.step("Task B")
  val stepC = WIO.draft.step("Task C")

  val parallelWorkflow = WIO.draft.parallel(stepA, stepB, stepC)
  // end_draft
}

