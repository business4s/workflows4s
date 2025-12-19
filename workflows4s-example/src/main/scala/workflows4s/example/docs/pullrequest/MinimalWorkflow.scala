package workflows4s.example.docs.pullrequest

import cats.effect.IO
import workflows4s.mermaid.MermaidRenderer
import workflows4s.runtime.{InMemoryRuntime, InMemoryWorkflowInstance}
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.cats.CatsEffect.given
import workflows4s.cats.IOWorkflowContext
import cats.effect.unsafe.implicits.global

object MinimalWorkflow {

  def main(args: Array[String]): Unit = {
    object Context extends IOWorkflowContext {
      override type State = String
    }
    import Context.*

    val hello    = WIO.pure("Hello").autoNamed
    val world    = WIO.pure.makeFrom[String].value(_ + " World!").autoNamed
    val workflow = hello >>> world

    println(MermaidRenderer.renderWorkflow(workflow.toProgress).toViewUrl)

    val engine     = WorkflowInstanceEngine.basic[IO]()
    val runtime    = InMemoryRuntime.create[IO, Context.Ctx](workflow, "", engine)
    val wfInstance = runtime.createInstance("id").unsafeRunSync().asInstanceOf[InMemoryWorkflowInstance[IO, Context.Ctx]]

    wfInstance.wakeup().unsafeRunSync()

    println(wfInstance.queryState().unsafeRunSync())
    // Hello World!

    // render workflow definition
    println(MermaidRenderer.renderWorkflow(wfInstance.getProgress.unsafeRunSync()).toViewUrl)

  }

}
