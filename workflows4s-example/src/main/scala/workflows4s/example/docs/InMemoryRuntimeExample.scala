package workflows4s.example.docs

import cats.effect.IO
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.runtime.{InMemoryRuntime, InMemorySyncRuntime, InMemorySyncWorkflowInstance, InMemoryWorkflowInstance}
import workflows4s.wio.WorkflowContext

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
    val workflow: WIO.Initial                               = ???
    val knockerUpperAgent: KnockerUpper.Agent[MyWorkflowId] = ???
    val runtime: InMemoryRuntime[Ctx, MyWorkflowId]         = InMemoryRuntime.default(workflow, InitialState(), knockerUpperAgent)
    val wfInstance: IO[InMemoryWorkflowInstance[Ctx]]       = runtime.createInstance(??? : MyWorkflowId)
    // async_doc_end
  }

  object Sync {
    // ssync_doc_start
    import MyWorkflowCtx.*
    val workflow: WIO.Initial                         = ???
    val runtime: InMemorySyncRuntime[Ctx, Unit]       = InMemorySyncRuntime.default(workflow, InitialState())
    val wfInstance: InMemorySyncWorkflowInstance[Ctx] = runtime.createInstance(())
    // ssync_doc_end
  }

}
