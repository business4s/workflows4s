package workflows4s.example.docs.draft

object DraftSignalExample {

  import workflows4s.wio.DraftWorkflowContext.*

  // start_draft
  val awaitApproval = WIO.draft.signal("Approval Required", error = "Rejected")
  // end_draft

}
