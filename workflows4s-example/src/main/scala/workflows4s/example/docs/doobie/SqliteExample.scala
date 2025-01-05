package workflows4s.example.docs.doobie

import cats.effect.IO
import doobie.util.transactor.Transactor
import workflows4s.doobie.EventCodec
import workflows4s.doobie.sqlite.{SqliteRuntime, WorkflowId}
import workflows4s.runtime.WorkflowInstance
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.wio.{WCState, WorkflowContext}

object SqliteExample {
  object MyWorkflowCtx extends WorkflowContext {
    sealed trait State
    case class InitialState() extends State
    sealed trait Event
  }

  // doc_start
  import MyWorkflowCtx.*
  val knockerUpper: KnockerUpper.Agent[WorkflowId] = ???
  val workflow: WIO.Initial                        = ???
  val initialState: State                          = ???
  val eventCodec: EventCodec[Event]                = ???
  val transactor: Transactor[IO]                   = ???

  val runtime: SqliteRuntime[Ctx] =
    SqliteRuntime.default(workflow = workflow, initialState = InitialState(), eventCodec = eventCodec, xa = transactor, knockerUpper = knockerUpper)

  val wfInstance: IO[WorkflowInstance[IO, WCState[Ctx]]] = runtime.createInstance(WorkflowId(1L))

  // doc_end
}
