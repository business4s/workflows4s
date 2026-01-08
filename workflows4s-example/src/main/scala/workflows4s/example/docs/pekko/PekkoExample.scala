package workflows4s.example.docs.pekko

import org.apache.pekko.actor.typed.ActorSystem
import workflows4s.runtime.WorkflowInstance
import workflows4s.runtime.instanceengine.{LazyFuture, WorkflowInstanceEngine}
import workflows4s.runtime.pekko.PekkoRuntime
import workflows4s.wio.LazyFutureWorkflowContext

import scala.concurrent.ExecutionContext

object PekkoExample {

  // doc_start
  object MyWorkflowCtx extends LazyFutureWorkflowContext {
    sealed trait State
    case class InitialState() extends State
    sealed trait Event
  }

  import MyWorkflowCtx.*
  given ExecutionContext                         = ???
  given ActorSystem[?]                           = ???
  val engine: WorkflowInstanceEngine[LazyFuture] = ???
  val workflow: WIO.Initial                      = ???

  val runtime: PekkoRuntime[Ctx] = PekkoRuntime.create("my-workflow", workflow, InitialState(), engine)

  runtime.initializeShard()

  // Pekko runtime returns LazyFuture-based instances (call .run to convert to Future)
  val instance: WorkflowInstance[LazyFuture, State] = runtime.createInstance_("my-workflow-id")
  // doc_end

}
