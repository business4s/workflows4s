package workflows4s.example.docs.visualization

import org.camunda.bpm.model.bpmn.Bpmn
import workflows4s.bpmn.BPMNConverter
import workflows4s.example.docs.pullrequest.PullRequestWorkflow
import workflows4s.wio.WIO
import workflows4s.wio.model.WIOModelInterpreter

object BPMNExample {

  // start_doc
  val wio: WIO[?, ?, ?, ?] = PullRequestWorkflow.workflow
  val model                = WIOModelInterpreter.run(wio)
  val bpmnModel            = BPMNConverter.convert(model, "process")
  val bpmnXml              = Bpmn.convertToString(bpmnModel)
  // end_doc

}
