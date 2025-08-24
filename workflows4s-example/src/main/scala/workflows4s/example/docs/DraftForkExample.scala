package workflows4s.example.docs

object DraftForkExample {
  object DraftContext extends workflows4s.wio.WorkflowContext {
    // No need to define State or Event
  }

  import DraftContext._

  // start_draft
  val approveStep = WIO.draft.step("Approve")
  val rejectStep = WIO.draft.step("Reject")
  
  val approvalWorkflow = WIO.draft.choice("Review Decision")(
    "Approved" -> approveStep,
    "Rejected" -> rejectStep
  )
  // end_draft
}

