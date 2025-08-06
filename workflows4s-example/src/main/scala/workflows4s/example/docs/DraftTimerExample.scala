package workflows4s.example.docs

import scala.concurrent.duration._

object DraftTimerExample {
  object DraftContext extends workflows4s.wio.WorkflowContext {
    // No need to define State or Event
  }

  import DraftContext._

  // start_draft
  // Create timer operation with draft API
  val waitForReview = WIO.draft.timer("Wait for Review", duration = 24.hours)
  // end_draft
}