package workflows4s.example.docs.visualization

import org.camunda.bpm.model.bpmn.Bpmn
import workflows4s.bpmn.BPMNConverter
import workflows4s.example.docs.pullrequest.PullRequestWorkflow
import workflows4s.wio.WIO

object BPMNExample {

  // start_doc
  val wio: WIO[?, ?, ?, ?] = PullRequestWorkflow.workflow
  val bpmnModel            = BPMNConverter.convert(wio.toModel, "process")
  val bpmnXml              = Bpmn.convertToString(bpmnModel)
  // end_doc

}
