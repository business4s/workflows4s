package workflows4s.example.docs.draft

import scala.concurrent.duration.*

object DraftTimerExample {

  import workflows4s.wio.DraftWorkflowContext.*

  // start_draft
  // Create timer operation with draft API
  val waitForReview = WIO.draft.timer(duration = 24.hours)
  // end_draft

}
