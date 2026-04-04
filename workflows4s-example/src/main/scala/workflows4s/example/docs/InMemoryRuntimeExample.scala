package workflows4s.example.docs

import cats.effect.IO
import workflows4s.runtime.{
  InMemoryConcurrentRuntime,
  InMemoryConcurrentWorkflowInstance,
  InMemorySynchronizedRuntime,
  InMemorySynchronizedWorkflowInstance,
}
import workflows4s.wio.WorkflowContext
import cats.effect.unsafe.implicits.global
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine

object InMemorySynchronizedRuntimeExample {

  object MyWorkflowCtx extends WorkflowContext {
    sealed trait State
    case class InitialState() extends State
    sealed trait Event
  }
  trait MyWorkflowId

  object Async {
    // async_doc_start
    import MyWorkflowCtx.*
    val workflow: WIO.Initial                                       = ???
    val engine: WorkflowInstanceEngine[IO]                          = ???
    val runtime: InMemoryConcurrentRuntime[IO, Ctx]                 = InMemoryConcurrentRuntime
      .default(workflow, InitialState(), engine)
      .unsafeRunSync()
    val wfInstance: IO[InMemoryConcurrentWorkflowInstance[IO, Ctx]] = runtime.createInstance("my-workflow-1")
    // async_doc_end
  }

  object Sync {
    // ssync_doc_start
    import MyWorkflowCtx.*
    val workflow: WIO.Initial                                         = ???
    val engine: WorkflowInstanceEngine[IO]                            = ???
    val runtime: InMemorySynchronizedRuntime[IO, Ctx]                 = InMemorySynchronizedRuntime.create(workflow, InitialState(), engine)
    val wfInstance: IO[InMemorySynchronizedWorkflowInstance[IO, Ctx]] = runtime.createInstance("my-workflow-1")
    // ssync_doc_end
  }

}
