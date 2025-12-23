package workflows4s.example.docs.draft

import scala.concurrent.duration.*

object DraftInterruptionExample {

  import workflows4s.wio.DraftWorkflowContext.*

  // start_draft
  // Create signal and timeout interruptions
  val urgentProcessing  = WIO.draft.interruptionSignal("Urgent Processing Request")
  val processingTimeout = WIO.draft.interruptionTimeout("Processing Deadline", 2.hours)

  // Document processing workflow that can be interrupted
  val documentProcessingWorkflow =
    WIO.draft.step("Validate Document") >>>
      WIO.draft
        .step("Extract Content")
        .interruptWith(urgentProcessing)  // Can be interrupted for urgent processing
        .interruptWith(processingTimeout) // Must complete within 2 hours
  // end_draft

}
