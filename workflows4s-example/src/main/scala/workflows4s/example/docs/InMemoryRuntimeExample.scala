package workflows4s.example.docs

import cats.effect.IO
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.runtime.{InMemoryRuntime, InMemorySyncRuntime, InMemorySyncWorkflowInstance, InMemoryWorkflowInstance}
import workflows4s.wio.WorkflowContext
import cats.effect.unsafe.implicits.global

object InMemoryRuntimeExample {

  object MyWorkflowCtx extends WorkflowContext {
    sealed trait State
    case class InitialState() extends State
    sealed trait Event
  }
  trait MyWorkflowId

  object Async {
    // async_doc_start
    import MyWorkflowCtx.*
    val workflow: WIO.Initial                         = ???
    val knockerUpperAgent: KnockerUpper.Agent         = ???
    val runtime: InMemoryRuntime[Ctx]                 = InMemoryRuntime
      .default(workflow, InitialState(), knockerUpperAgent)
      .unsafeRunSync()
    val wfInstance: IO[InMemoryWorkflowInstance[Ctx]] = runtime.createInstance("my-workflow-1")
    // async_doc_end
  }

  object Sync {
    // ssync_doc_start
    import MyWorkflowCtx.*
    val workflow: WIO.Initial                         = ???
    val runtime: InMemorySyncRuntime[Ctx]             = InMemorySyncRuntime.default(workflow, InitialState())
    val wfInstance: InMemorySyncWorkflowInstance[Ctx] = runtime.createInstance("my-workflow-1")
    // ssync_doc_end
  }

}
