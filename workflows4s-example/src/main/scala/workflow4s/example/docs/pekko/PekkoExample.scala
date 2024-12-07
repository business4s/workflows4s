package workflow4s.example.docs.pekko

import cats.effect.unsafe.IORuntime
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.sharding.typed.scaladsl.EntityContext
import workflow4s.example.docs.pekko.PekkoExample.MyWorkflowCtx.InitialState
import workflow4s.runtime.WorkflowInstance
import workflow4s.runtime.wakeup.KnockerUpper
import workflow4s.wio.{WCState, WorkflowContext}
import workflows4s.doobie.EventCodec
import workflows4s.runtime.pekko.PekkoRuntime

import scala.concurrent.Future

object PekkoExample {

  object MyWorkflowCtx extends WorkflowContext {
    sealed trait State
    case class InitialState() extends State
    sealed trait Event
  }

  // doc_start
  import MyWorkflowCtx.*
  given IORuntime                                    = ???
  given ActorSystem[?]                               = ???
  val workflow: WIO.Initial[InitialState]            = ???
  val initialState: EntityContext[?] => InitialState = ???

  val runtime: PekkoRuntime[Ctx] = PekkoRuntime.create("my-workflow", workflow, initialState)

  runtime.initializeShard()

  val instance: WorkflowInstance[Future, State] = runtime.createInstance("my-workflow-id")
  // doc_end

}
