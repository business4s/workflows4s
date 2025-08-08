package workflows4s.example.docs

import scala.concurrent.duration._

object DraftLoopExample {
  object DraftContext extends workflows4s.wio.WorkflowContext {
    // No need to define State or Event
  }

  import DraftContext._

  // start_draft
  // Create a draft loop with a timer to avoid busy loops
  val processStep = WIO.draft.step("Process Item")
  val waitStep = WIO.draft.timer("Wait before retry", duration = 1.minute)
  
  val loop = WIO.draft.repeat(
    conditionName = "Is processing complete?",
    releaseBranchName = "Yes",
    restartBranchName = "No"
  )(
    body = processStep >>> waitStep,
    onRestart = WIO.draft.step("Reset for retry")
  )
  // end_draft
}

