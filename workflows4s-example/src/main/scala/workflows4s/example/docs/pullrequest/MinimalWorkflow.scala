package workflows4s.example.docs.pullrequest

import cats.effect.IO
import workflows4s.mermaid.MermaidRenderer
import cats.effect.unsafe.implicits.global
import workflows4s.runtime.InMemorySynchronizedRuntime
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.wio.WorkflowContext
import workflows4s.wio.cats.effect.WeakSyncInstances.given

object MinimalWorkflow {

  def main(args: Array[String]): Unit = {
    object Context extends WorkflowContext {
      type Effect         = IO
      override type State = String
    }
    import Context.*

    val hello    = WIO.pure("Hello").autoNamed
    val world    = WIO.pure.makeFrom[String].value(_ + " World!").autoNamed
    val workflow = hello >>> world

    println(MermaidRenderer.renderWorkflow(workflow.toProgress).toViewUrl)

    val engine     = WorkflowInstanceEngine.basic[IO, Context.Ctx]()
    val runtime    = InMemorySynchronizedRuntime.create[IO, Context.Ctx](workflow, "", engine)
    val wfInstance = runtime.createInstance("id").unsafeRunSync()

    wfInstance.wakeup().unsafeRunSync()

    println(wfInstance.queryState().unsafeRunSync())
    // Hello World!

    // render workflow definition
    println(MermaidRenderer.renderWorkflow(wfInstance.getProgress.unsafeRunSync()).toViewUrl)

  }

}
