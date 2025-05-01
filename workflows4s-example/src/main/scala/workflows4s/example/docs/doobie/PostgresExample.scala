package workflows4s.example.docs.doobie

import cats.effect.IO
import doobie.util.transactor.Transactor
import workflows4s.doobie.postgres.{PostgresWorkflowStorage, WorkflowId}
import workflows4s.doobie.{ByteCodec, DatabaseRuntime, WorkflowStorage}
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
  given eventCodec: ByteCodec[Event]               = ???
  val transactor: Transactor[IO]                   = ???
  val storage: WorkflowStorage[WorkflowId, Event]  = new PostgresWorkflowStorage[Event]()

  val runtime: DatabaseRuntime[Ctx, WorkflowId]          = DatabaseRuntime.default(workflow, initialState, transactor, knockerUpper, storage)
  val wfInstance: IO[WorkflowInstance[IO, WCState[Ctx]]] = runtime.createInstance(WorkflowId(1L))
  // doc_end

}
