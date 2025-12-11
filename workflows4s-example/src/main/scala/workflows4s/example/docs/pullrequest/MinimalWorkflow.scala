package workflows4s.example.docs.pullrequest

import cats.Id
import workflows4s.effect.Effect.idEffect
import workflows4s.mermaid.MermaidRenderer
import workflows4s.runtime.InMemorySyncRuntime
import workflows4s.runtime.instanceengine.BasicJavaTimeEngine
import workflows4s.wio.WorkflowContext

import java.time.Clock

object MinimalWorkflow {

  def main(args: Array[String]): Unit = {
    object Context extends WorkflowContext {
      override type State = String
      override type F[A]  = cats.effect.IO[A]
    }
    import Context.*

    val hello    = WIO.pure("Hello").autoNamed
    val world    = WIO.pure.makeFrom[String].value(_ + " World!").autoNamed
    val workflow = hello >>> world

    println(MermaidRenderer.renderWorkflow(workflow.toProgress).toViewUrl)

    val engine     = new BasicJavaTimeEngine[Id](Clock.systemUTC())
    val runtime    = InMemorySyncRuntime.create[Context.Ctx](workflow, "", engine)
    val wfInstance = runtime.createInstance("id")

    wfInstance.wakeup()

    println(wfInstance.queryState())
    // Hello World!

    // render workflow definition
    println(MermaidRenderer.renderWorkflow(wfInstance.getProgress).toViewUrl)

  }

}
