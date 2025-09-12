package workflows4s.example.docs

import scala.concurrent.duration.*

object DraftTimerExample {

  import workflows4s.wio.DraftWorkflowContext.*

  // start_draft
  // Create timer operation with draft API
  val waitForReview = WIO.draft.timer("Wait for Review", duration = 24.hours)
  // end_draft

}
