package workflows4s.example.docs.doobie

import cats.effect.IO
import doobie.util.transactor.Transactor
import workflows4s.example.docs.doobie.PostgresExample.MyWorkflowCtx.InitialState
import workflows4s.runtime.WorkflowInstance
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.wio.{WCState, WorkflowContext}
import workflows4s.doobie.EventCodec
import workflows4s.doobie.postgres.{PostgresRuntime, WorkflowId}

object PostgresExample {

  object MyWorkflowCtx extends WorkflowContext {
    sealed trait State
    case class InitialState() extends State
    sealed trait Event
  }

  // doc_start
  import MyWorkflowCtx.*
  val knockerUpper: KnockerUpper.Factory[WorkflowId] = ???
  val workflow: WIO[InitialState, Nothing, State]    = ???
  val initialState: State                            = ???
  val eventCodec: EventCodec[Event]                  = ???
  val transactor: Transactor[IO]                     = ???

  val runtime: PostgresRuntime[Ctx, InitialState]        = PostgresRuntime.default(workflow, eventCodec, transactor, knockerUpper)
  val wfInstance: IO[WorkflowInstance[IO, WCState[Ctx]]] = runtime.createInstance(WorkflowId(1L), InitialState())
  // doc_end

}
