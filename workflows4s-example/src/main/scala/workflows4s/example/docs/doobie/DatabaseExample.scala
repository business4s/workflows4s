package workflows4s.example.docs.doobie

import cats.effect.IO
import doobie.util.transactor.Transactor
import workflows4s.doobie.postgres.PostgresWorkflowStorage
import workflows4s.doobie.{ByteCodec, WorkflowStorage}
import workflows4s.wio.WorkflowContext

import scala.annotation.nowarn

@nowarn
object DatabaseExample {

  // Database runtimes use an internal effect type (Result) for transactions
  // The workflow and engine must use this internal type
  // Below shows the conceptual pattern - see full example in tests

  // doc_start
  // Define your workflow context
  trait MyCtx extends WorkflowContext {
    type State = String
    type Event = String
  }
  type Ctx = MyCtx

  val transactor: Transactor[IO]       = ???
  val storage: WorkflowStorage[String] = ???
  val templateId                       = "my-workflow"

  // For actual usage, see workflows4s-doobie tests
  // DatabaseRuntime.create takes:
  // - workflow: WIO.Initial using the internal Result type
  // - initialState: WCState[Ctx]
  // - transactor: Transactor[IO]
  // - engine: WorkflowInstanceEngine using the internal Result type
  // - storage: WorkflowStorage[WCEvent[Ctx]]
  // - templateId: String

  // After creation:
  // val runtime: DatabaseRuntime[Ctx] = DatabaseRuntime.create(...)
  // val wfInstance: IO[WorkflowInstance[IO, WCState[Ctx]]] = runtime.createInstance("1")
  // doc_end

  {
    // doc_postgres_start
    given eventCodec: ByteCodec[String] = ???

    val postgresStorage: WorkflowStorage[String] = new PostgresWorkflowStorage[String]()
    // doc_postgres_end
  }

}
