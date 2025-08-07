package workflows4s.example.docs

import scala.concurrent.duration._

object DraftLoopExample {
  object DraftContext extends workflows4s.wio.WorkflowContext {
    // No need to define State or Event
  }

  import DraftContext._

  // start_draft
  // Create a retry workflow with a timer to avoid busy loops
  val processStep = WIO.draft.step("Process Item")
  val waitStep = WIO.draft.timer("Wait before retry", 5.seconds)
  
  val retryWorkflow = WIO.draft.repeat("Check Success", "Done", "Retry")(
    processStep >>> waitStep
  )

  // Add custom retry behavior
  val handleError = WIO.draft.step("Handle Error")
  val retryWithHandler = WIO.draft.repeat("Check Success", "Done", "Retry")(
    processStep >>> waitStep,
    onRestart = handleError
  )
  // end_draft
}

