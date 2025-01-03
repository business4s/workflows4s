package workflows4s.example.docs.visualization

import org.camunda.bpm.model.bpmn.Bpmn
import workflows4s.bpmn.BPMNConverter
import workflows4s.example.docs.pullrequest.PullRequestWorkflow
import workflows4s.mermaid.MermaidRenderer
import workflows4s.wio.WIO
import workflows4s.wio.model.WIOModelInterpreter

object MermaidExample {

  // start_doc
  val wio: WIO[?, ?, ?, ?] = PullRequestWorkflow.workflow
  val model                = WIOModelInterpreter.run(wio)
  val mermaidString        = MermaidRenderer.renderWorkflow(model)
  // end_doc

}
