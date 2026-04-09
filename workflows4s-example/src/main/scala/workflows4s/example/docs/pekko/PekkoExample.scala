package workflows4s.example.docs.pekko

import scala.concurrent.Future
import org.apache.pekko.actor.typed.ActorSystem
import workflows4s.runtime.WorkflowInstance
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.runtime.pekko.PekkoRuntime
import workflows4s.wio.WorkflowContext

object PekkoExample {

  object MyWorkflowCtx extends WorkflowContext {
    type Effect[T] = Future[T]
    sealed trait State
    case class InitialState() extends State
    sealed trait Event
  }

  // doc_start
  import MyWorkflowCtx.*
  given ActorSystem[?]                            = ???
  val engine: WorkflowInstanceEngine[Future, Ctx] = ???
  val workflow: WIO.Initial                       = ???

  val runtime: PekkoRuntime[Ctx] = PekkoRuntime.create("my-workflow", workflow, InitialState(), engine)

  runtime.initializeShard()

  val instance: WorkflowInstance[Future, State] = runtime.createInstance_("my-workflow-id")
  // doc_end

}
