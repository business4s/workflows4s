package workflows4s.example.docs

object DraftSignalExample {

  import workflows4s.wio.DraftWorkflowContext.*

  // start_draft
  // Create a signal operation
  val awaitApproval = WIO.draft.signal("Approval Required", error = "Rejected")

  // Use it in a workflow
  val workflow = WIO.draft.step("Submit PR") >>> awaitApproval
  // end_draft

}
