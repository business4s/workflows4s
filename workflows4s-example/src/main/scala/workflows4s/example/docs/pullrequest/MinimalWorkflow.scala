package workflows4s.example.docs.pullrequest

import workflows4s.mermaid.MermaidRenderer
import workflows4s.runtime.InMemorySyncRuntime
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
    // https://mermaid.live/edit#pako:flowchart+TD%0Anode0%40%7B+shape%3A+circle%2C+label%3A+%22Start%22%7D%0Anode1%5B%22Hello%22%5D%0Anode0+--%3E+node1%0Anode2%5B%22World%22%5D%0Anode1+--%3E+node2%0A

    val runtime    = InMemorySyncRuntime.default[Context.Ctx, String](workflow, "")
    val wfInstance = runtime.createInstance("id")

    wfInstance.wakeup()

    println(wfInstance.queryState())
    // Hello World!

    // render workflow definition
    println(MermaidRenderer.renderWorkflow(wfInstance.getProgress).toViewUrl)

  }

}
