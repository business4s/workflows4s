package workflows4s.example.docs

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import workflows4s.runtime.cats.effect.{InMemoryConcurrentRuntime, InMemoryConcurrentWorkflowInstance}
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.wio.WorkflowContext

object InMemoryConcurrentRuntimeExample {

  object MyWorkflowCtx extends WorkflowContext {
    type Effect[T] = IO[T]
    sealed trait State
    case class InitialState() extends State
    sealed trait Event
  }

  // concurrent_doc_start
  import MyWorkflowCtx.*
  val workflow: WIO.Initial                                       = ???
  val engine: WorkflowInstanceEngine[IO, Ctx]                     = ???
  val runtime: InMemoryConcurrentRuntime[IO, Ctx]                 = InMemoryConcurrentRuntime
    .default(workflow, InitialState(), engine)
    .unsafeRunSync()
  val wfInstance: IO[InMemoryConcurrentWorkflowInstance[IO, Ctx]] = runtime.createInstance("my-workflow-1")
  // concurrent_doc_end

}
