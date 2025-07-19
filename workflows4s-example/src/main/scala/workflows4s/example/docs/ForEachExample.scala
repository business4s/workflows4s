package workflows4s.example.docs

import workflows4s.example.docs.Context.WIO

object ForEachExample {

  object draft {
    // draft_start
    val subWorkflow  = WIO.draft.step()
    val forEachDraft = WIO.draft.forEach(subWorkflow)
    // draft_end
  }
}
