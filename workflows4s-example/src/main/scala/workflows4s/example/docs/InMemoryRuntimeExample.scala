package workflows4s.example.docs

import cats.effect.IO
import workflows4s.runtime.{InMemoryRuntime, InMemorySyncRuntime, InMemorySyncWorkflowInstance, InMemoryWorkflowInstance}
import workflows4s.wio.WorkflowContext

object InMemoryRuntimeExample {

  object MyWorkflowCtx extends WorkflowContext {
    sealed trait State

    case class InitialState() extends State

    sealed trait Event
  }

  object Async {
    // async_doc_start
    import MyWorkflowCtx.*
    val workflow: WIO.Initial[InitialState]               = ???
    val runtime: InMemoryRuntime[Ctx, Unit, InitialState] = InMemoryRuntime.default(workflow)
    val wfInstance: IO[InMemoryWorkflowInstance[Ctx]]     = runtime.createInstance((), InitialState())
    // async_doc_end
  }

  object Sync {
    // ssync_doc_start
    import MyWorkflowCtx.*
    val workflow: WIO.Initial[InitialState]                   = ???
    val runtime: InMemorySyncRuntime[Ctx, Unit, InitialState] = InMemorySyncRuntime.default(workflow)
    val wfInstance: InMemorySyncWorkflowInstance[Ctx]         = runtime.createInstance((), InitialState())
    // ssync_doc_end
  }

}
