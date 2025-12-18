package workflows4s.example.docs.visualization

import workflows4s.example.docs.pullrequest.PullRequestWorkflow
import workflows4s.wio.internal.DebugRenderer
import workflows4s.runtime.WorkflowInstance
import workflows4s.wio.WIO

import scala.annotation.nowarn

@nowarn("msg=unused local definition")
object DebugExample {

  // start_doc
  val wio: WIO[?, ?, ?, ?, ?] = PullRequestWorkflow.workflow
  val debugString             = DebugRenderer.getCurrentStateDescription(wio.toProgress)
  // end_doc

  {
    // start_progress
    val instance: WorkflowInstance[cats.Id, ?] = ???
    val debugString                            = DebugRenderer.getCurrentStateDescription(instance.getProgress)
    // end_progress
  }
}
