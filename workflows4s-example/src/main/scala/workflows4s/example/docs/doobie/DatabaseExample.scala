package workflows4s.example.docs.doobie

import cats.effect.IO
import doobie.util.transactor.Transactor
import workflows4s.doobie.postgres.PostgresWorkflowStorage
import workflows4s.doobie.{ByteCodec, DatabaseRuntime, WorkflowStorage}
import workflows4s.runtime.WorkflowInstance
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.wio.{WCState, WorkflowContext}

import scala.annotation.nowarn

@nowarn("msg=unused local definition")
object DatabaseExample {

  object MyWorkflowCtx extends WorkflowContext {
    sealed trait State
    case class InitialState() extends State
    sealed trait Event
  }

  import MyWorkflowCtx.*
  {
    // doc_start
    val workflow: WIO.Initial            = ???
    val initialState: State              = ???
    val transactor: Transactor[IO]       = ???
    val storage: WorkflowStorage[Event]  = ???
    val knockerUpper: KnockerUpper.Agent = ???
    val runtimeId                        = "my-workflow"

    val runtime: DatabaseRuntime[Ctx]                      = DatabaseRuntime.default(workflow, initialState, transactor, knockerUpper, storage, runtimeId)
    val wfInstance: IO[WorkflowInstance[IO, WCState[Ctx]]] = runtime.createInstance("1")
    // doc_end
  }

  {
    // doc_postgres_start
    given eventCodec: ByteCodec[Event] = ???

    val storage: WorkflowStorage[Event] = new PostgresWorkflowStorage[Event]()
    // doc_postgres_end
  }

}
