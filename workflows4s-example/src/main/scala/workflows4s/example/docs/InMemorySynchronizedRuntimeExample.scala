package workflows4s.example.docs

import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.runtime.{InMemorySynchronizedRuntime, InMemorySynchronizedWorkflowInstance}
import workflows4s.wio.WorkflowContext

import scala.util.Try

object InMemorySynchronizedRuntimeExample {

  object MyWorkflowCtx extends WorkflowContext {
    type Effect[T] = Try[T]
    sealed trait State
    case class InitialState() extends State
    sealed trait Event
  }

  // synchronized_doc_start
  import MyWorkflowCtx.*
  val workflow: WIO.Initial                                           = ???
  val engine: WorkflowInstanceEngine[Try, Ctx]                        = ???
  val runtime: InMemorySynchronizedRuntime[Try, Ctx]                  = InMemorySynchronizedRuntime.create(workflow, InitialState(), engine)
  val wfInstance: Try[InMemorySynchronizedWorkflowInstance[Try, Ctx]] = runtime.createInstance("my-workflow-1")
  // synchronized_doc_end

}
