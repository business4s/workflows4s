package workflows4s.example.docs.pullrequest

import workflows4s.mermaid.MermaidRenderer
import workflows4s.runtime.InMemorySynchronizedRuntime
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.wio.WorkflowContext

import scala.util.Try

object MinimalWorkflow {

  def main(args: Array[String]): Unit = {
    object Context extends WorkflowContext {
      type Effect         = Try
      override type State = String
    }
    import Context.*

    val hello    = WIO.pure("Hello").autoNamed
    val world    = WIO.pure.makeFrom[String].value(_ + " World!").autoNamed
    val workflow = hello >>> world

    println(MermaidRenderer.renderWorkflow(workflow.toProgress).toViewUrl)

    val engine     = WorkflowInstanceEngine.basic[Try, Context.Ctx]()
    val runtime    = InMemorySynchronizedRuntime.create[Try, Context.Ctx](workflow, "", engine)
    val wfInstance = runtime.createInstance("id").get

    wfInstance.wakeup().get

    println(wfInstance.queryState().get)
    // Hello World!

    // render workflow definition
    println(MermaidRenderer.renderWorkflow(wfInstance.getProgress.get).toViewUrl)

  }

}
