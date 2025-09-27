package workflows4s.example.docs.draft

import workflows4s.wio.DraftWorkflowContext.*

object DraftCheckpointExample {

  // start_draft_checkpoint
  val base = WIO.draft.step()

  val checkpointed = WIO.draft.checkpoint(base)

  // or with a postfix application
  import WIO.draft.syntax.*
  val checkpointed2 = base.draftCheckpointed
  // end_draft_checkpoint

  // start_draft_recovery
  val recovery = WIO.draft.recovery
  // end_draft_recovery
}
