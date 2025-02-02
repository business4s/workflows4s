package workflows4s.example.docs.visualization

import workflows4s.example.docs.pullrequest.PullRequestWorkflow
import workflows4s.mermaid.MermaidRenderer
import workflows4s.wio.WIO

object MermaidExample {

  // start_doc
  val wio: WIO[?, ?, ?, ?] = PullRequestWorkflow.workflow
  val mermaidString        = MermaidRenderer.renderWorkflow(wio.toProgress)
  // end_doc

}
