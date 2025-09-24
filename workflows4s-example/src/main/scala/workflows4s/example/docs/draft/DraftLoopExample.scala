package workflows4s.example.docs.draft

import scala.concurrent.duration.*

object DraftLoopExample {

  import workflows4s.wio.DraftWorkflowContext.*

  // start_draft
  // Create a draft loop with a timer to avoid busy loops
  val processStep = WIO.draft.step("Process Item")
  val waitStep    = WIO.draft.timer("Wait before retry", duration = 1.minute)

  val loop = WIO.draft.repeat(
    conditionName = "Is processing complete?",
    releaseBranchName = "Yes",
    restartBranchName = "No",
  )(
    body = processStep >>> waitStep,
    onRestart = WIO.draft.step("Reset for retry"),
  )
  // end_draft
}
