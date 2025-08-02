package workflows4s.example.docs.pullrequest

import workflows4s.mermaid.MermaidRenderer
import workflows4s.runtime.InMemorySyncRuntime
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.wio.WorkflowContext

object MinimalWorkflow {

  def main(args: Array[String]): Unit = {
    object Context extends WorkflowContext {
      override type State = String
    }
    import Context.*

    val hello    = WIO.pure("Hello").autoNamed
    val world    = WIO.pure.makeFrom[String].value(_ + " World!").autoNamed
    val workflow = hello >>> world

    println(MermaidRenderer.renderWorkflow(workflow.toProgress).toViewUrl)

    val engine     = WorkflowInstanceEngine.basic
    val runtime    = InMemorySyncRuntime.create[Context.Ctx](workflow, "", engine)
    val wfInstance = runtime.createInstance("id")

    wfInstance.wakeup()

    println(wfInstance.queryState())
    // Hello World!

    // render workflow definition
    println(MermaidRenderer.renderWorkflow(wfInstance.getProgress).toViewUrl)

  }

}
