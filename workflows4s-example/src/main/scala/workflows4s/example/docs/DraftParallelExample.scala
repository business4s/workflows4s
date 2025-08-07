package workflows4s.example.docs

object DraftParallelExample {
  object DraftContext extends workflows4s.wio.WorkflowContext {
    // No need to define State or Event
  }

  import DraftContext._

  // start_draft
  // Create a simple parallel workflow
  val stepA = WIO.draft.step("Task A")
  val stepB = WIO.draft.step("Task B")
  val stepC = WIO.draft.step("Task C")

  val parallelWorkflow = WIO.draft.parallel(stepA, stepB, stepC)

  // Create a parallel workflow with timers and signals
  val timerStep = WIO.draft.timer("Wait 1")
  val signalStep = WIO.draft.signal("External Approval")
  val parallelWithWaits = WIO.draft.parallel(stepA, timerStep, signalStep)
  // end_draft
}

