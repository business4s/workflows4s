package workflows4s.example.docs

object DraftForkExample {
  object DraftContext extends workflows4s.wio.WorkflowContext {
    // No need to define State or Event
  }

  import DraftContext._

  // start_draft
  // Create a simple approval workflow with conditional branching
  val approveStep = WIO.draft.step("Approve")
  val rejectStep = WIO.draft.step("Reject")
  
  val approvalWorkflow = WIO.draft.choice("Review Decision")(
    "Approved" -> approveStep,
    "Rejected" -> rejectStep
  )

  // Create a more complex workflow with multiple branches
  val processStep = WIO.draft.step("Process")
  val notifyStep = WIO.draft.step("Notify")
  val archiveStep = WIO.draft.step("Archive")
  
  val complexWorkflow = WIO.draft.choice("Action Type")(
    "Process" -> (processStep >>> notifyStep),
    "Archive" -> archiveStep,
    "Skip" -> WIO.draft.step("Skip Processing")
  )
  // end_draft
}

