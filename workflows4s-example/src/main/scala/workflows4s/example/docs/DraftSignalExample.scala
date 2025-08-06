package workflows4s.example.docs

object DraftSignalExample {

  // start_draft
  object DraftContext extends workflows4s.wio.WorkflowContext {
    // No need to define State or Event
  }

  import DraftContext._

  // Create a signal operation
  val awaitApproval = WIO.draft.signal("Approval Required", error = "Rejected")

  // Use it in a workflow
  val workflow = WIO.draft.step("Submit PR") >>> 
    awaitApproval >>> 
    WIO.draft.step("Merge PR")
  // end_draft

}