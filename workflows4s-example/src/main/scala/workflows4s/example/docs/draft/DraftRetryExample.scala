package workflows4s.example.docs.draft

object DraftRetryExample {

  import workflows4s.wio.DraftWorkflowContext.*

  // start_draft
  val apiCall = WIO.draft.step()

  val withRetry = WIO.draft.retry(apiCall)

  // or with a postfix application
  import WIO.draft.syntax.*
  val withRetry2 = apiCall.draftRetry
  // end_draft

}
