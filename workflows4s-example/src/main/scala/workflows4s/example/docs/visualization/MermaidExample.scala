package workflows4s.example.docs.visualization

import workflows4s.example.docs.pullrequest.PullRequestWorkflow
import workflows4s.mermaid.MermaidRenderer
import workflows4s.runtime.WorkflowInstance
import workflows4s.wio.WIO

import scala.annotation.nowarn

@nowarn("msg=unused local definition")
object MermaidExample {

  // start_doc
  val wio: WIO[?, ?, ?, ?, ?] = PullRequestWorkflow.workflow
  val mermaidString           = MermaidRenderer.renderWorkflow(wio.toProgress)
  // end_doc

  {
    // start_progress
    val instance: WorkflowInstance[cats.Id, ?] = ???
    val mermaidString                          = MermaidRenderer.renderWorkflow(instance.getProgress)
    // end_progress
  }
}
