package workflows4s.example.docs.pekko

import scala.concurrent.Future
import cats.effect.IO
import cats.effect.unsafe.IORuntime
import org.apache.pekko.actor.typed.ActorSystem
import workflows4s.runtime.WorkflowInstance
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.runtime.pekko.PekkoRuntime
import workflows4s.wio.WorkflowContext

object PekkoExample {

  object MyWorkflowCtx extends WorkflowContext {
    sealed trait State
    case class InitialState() extends State
    sealed trait Event
    type F[A] = cats.effect.IO[A]
  }

  // doc_start
  import MyWorkflowCtx.*
  given IORuntime                        = ???
  given ActorSystem[?]                   = ???
  val engine: WorkflowInstanceEngine[IO] = ???
  val workflow: WIO.Initial              = ???

  val runtime: PekkoRuntime[Ctx] = PekkoRuntime.create("my-workflow", workflow, InitialState(), engine)

  runtime.initializeShard()

  val instance: WorkflowInstance[Future, State] = runtime.createInstance_("my-workflow-id")
  // doc_end

}
