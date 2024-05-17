package workflow4s.example.docs.pullrequest

import org.camunda.bpm.model.bpmn.Bpmn
import workflow4s.bpmn.BPMNConverter
import workflow4s.wio.WorkflowContext

import java.io.File

object PullRequestWorkflow {

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
    val bpmnModel = BPMNConverter.convert(workflow.getModel, "process")
    Bpmn.writeModelToFile(new File(s"pr.bpmn").getAbsoluteFile, bpmnModel)
    // end_render
  }

}
