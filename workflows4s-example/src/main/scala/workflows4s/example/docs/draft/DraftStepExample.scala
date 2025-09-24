package workflows4s.example.docs.draft

import workflows4s.wio.DraftWorkflowContext.*

object DraftStepExample {

  // start_doc
  val basic = WIO.draft.step()

  val withError = WIO.draft.step(error = "MyError")
  // end_doc
}
