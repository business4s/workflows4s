package workflows4s.example.docs.pekko

import cats.effect.unsafe.IORuntime
import org.apache.pekko.actor.typed.ActorSystem
import workflows4s.runtime.WorkflowInstance
import workflows4s.runtime.pekko.PekkoRuntime
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.wio.WorkflowContext

import scala.concurrent.Future

object PekkoExample {

  object MyWorkflowCtx extends WorkflowContext {
    sealed trait State
    case class InitialState() extends State
    sealed trait Event
  }

  // doc_start
  import MyWorkflowCtx.*
  given IORuntime                                               = ???
  given ActorSystem[?]                                          = ???
  val knockerUpper: KnockerUpper.Agent[PekkoRuntime.WorkflowId] = ???
  val workflow: WIO.Initial                                     = ???

  val runtime: PekkoRuntime[Ctx] = PekkoRuntime.create("my-workflow", workflow, InitialState(), knockerUpper)

  runtime.initializeShard()

  val instance: WorkflowInstance[Future, State] = runtime.createInstance_("my-workflow-id")
  // doc_end

}
