package workflow4s.example.docs.doobie

import cats.effect.IO
import doobie.util.transactor.Transactor
import workflow4s.example.docs.doobie.PostgresExample.MyWorkflowCtx.InitialState
import workflow4s.runtime.WorkflowInstance
import workflow4s.runtime.wakeup.KnockerUpper
import workflow4s.wio.{WCState, WorkflowContext}
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
