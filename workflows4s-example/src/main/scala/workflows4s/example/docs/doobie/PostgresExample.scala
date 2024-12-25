package workflows4s.example.docs.doobie

import cats.effect.IO
import doobie.util.transactor.Transactor
import workflows4s.doobie.EventCodec
import workflows4s.doobie.postgres.{PostgresRuntime, WorkflowId}
import workflows4s.runtime.WorkflowInstance
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.wio.{WCState, WorkflowContext}

object PostgresExample {

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

  val runtime: PostgresRuntime[Ctx]                      = PostgresRuntime.default(workflow, InitialState(), eventCodec, transactor, knockerUpper)
  val wfInstance: IO[WorkflowInstance[IO, WCState[Ctx]]] = runtime.createInstance(WorkflowId(1L))
  // doc_end

}
