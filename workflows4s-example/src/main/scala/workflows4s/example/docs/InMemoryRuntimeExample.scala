package workflows4s.example.docs

import cats.Id
import cats.effect.IO
import workflows4s.runtime.{InMemoryRuntime, WorkflowInstance}
import workflows4s.cats.CatsEffect.given
import workflows4s.cats.IOWorkflowContext
import workflows4s.wio.{SyncWorkflowContext, WCState}
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine

object InMemoryRuntimeExample {

  object MyWorkflowCtx extends IOWorkflowContext {
    sealed trait State
    case class InitialState() extends State
    sealed trait Event
  }

  object MySyncWorkflowCtx extends SyncWorkflowContext {
    sealed trait State
    case class InitialState() extends State
    sealed trait Event
  }

  trait MyWorkflowId

  object Async {
    // async_doc_start
    import MyWorkflowCtx.*
    val workflow: WIO.Initial                              = ???
    val engine: WorkflowInstanceEngine[IO]                 = ???
    val runtime: IO[InMemoryRuntime[IO, Ctx]]              =
      InMemoryRuntime.create[IO, Ctx](workflow, InitialState(), engine)
    val wfInstance: IO[WorkflowInstance[IO, WCState[Ctx]]] = runtime.flatMap(_.createInstance("my-workflow-1"))
    // async_doc_end
  }

  object Sync {
    // ssync_doc_start
    import MySyncWorkflowCtx.*
    // SyncWorkflowContext provides the Effect[Id] instance
    val workflow: WIO.Initial                          = ???
    val engine: WorkflowInstanceEngine[Id]             = ???
    val runtime: InMemoryRuntime[Id, Ctx]              = InMemoryRuntime.create[Id, Ctx](workflow, InitialState(), engine)
    val wfInstance: WorkflowInstance[Id, WCState[Ctx]] = runtime.createInstance("my-workflow-1")
    // ssync_doc_end
  }

}
