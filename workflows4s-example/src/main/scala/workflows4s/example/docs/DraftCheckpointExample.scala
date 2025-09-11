package workflows4s.example.docs

import workflows4s.wio.DraftWorkflowContext._

object DraftCheckpointExample {

  // start_draft_checkpoint
  val v1 = WIO.draft.checkpoint("CP") >>> WIO.draft.step("A") >>> WIO.draft.step("B")
  // end_draft_checkpoint

  // start_draft_recovery
  val v2 = WIO.draft.recovery("RC") >>> WIO.draft.step("C")
  // end_draft_recovery
}


