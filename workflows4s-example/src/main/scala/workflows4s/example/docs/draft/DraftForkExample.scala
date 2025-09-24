package workflows4s.example.docs.draft

object DraftForkExample {

  import workflows4s.wio.DraftWorkflowContext.*

  // start_draft
  val approveStep = WIO.draft.step("Approve")
  val rejectStep  = WIO.draft.step("Reject")

  val approvalWorkflow = WIO.draft.choice("Review Decision")(
    "Approved" -> approveStep,
    "Rejected" -> rejectStep,
  )
  // end_draft

}
