package workflows4s.example.docs.pullrequest

import java.io.File

import org.camunda.bpm.model.bpmn.Bpmn
import workflows4s.bpmn.BPMNConverter
import workflows4s.wio.WorkflowContext

object PullRequestWorkflowDraft {

  // start_context
  object Context extends WorkflowContext {
    override type Event = Unit
    override type State = Unit
  }
  import Context.*
  // end_context

  // start_steps
  val createPR: WIO.Draft    = WIO.draft.signal()
  val runPipeline: WIO.Draft = WIO.draft.step(error = "Critical Issue")
  val awaitReview: WIO.Draft = WIO.draft.signal(error = "Rejected")

  val mergePR: WIO.Draft = WIO.draft.step()
  val closePR: WIO.Draft = WIO.draft.step()

  val workflow: WIO.Draft = (
    createPR >>>
      runPipeline >>>
      awaitReview >>>
      mergePR
  ).handleErrorWith(closePR)
  // end_steps

  def main(args: Array[String]): Unit = {
    // start_render
    val bpmnModel = BPMNConverter.convert(workflow.toModel, "process")
    Bpmn.writeModelToFile(new File(s"pr-draft.bpmn").getAbsoluteFile, bpmnModel)
    // end_render
  }

}
