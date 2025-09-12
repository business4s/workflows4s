package workflows4s.example.docs

object DraftRetryExample {

  import workflows4s.wio.DraftWorkflowContext.*

  // start_draft
  // Create a step that might fail and needs retry
  val apiCall = WIO.draft.step("Call External API")

  // Wrap it with retry logic for visualization
  val withRetry = WIO.draft.retry(apiCall)

  // Use it in a workflow
  val workflow = WIO.draft.step("Prepare Request") >>> withRetry >>> WIO.draft.step("Process Response")
  // end_draft

}
