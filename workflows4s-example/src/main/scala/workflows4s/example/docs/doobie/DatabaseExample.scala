package workflows4s.example.docs.doobie

import cats.effect.IO
import doobie.util.transactor.Transactor
import workflows4s.doobie.postgres.PostgresWorkflowStorage
import workflows4s.doobie.{ByteCodec, WorkflowStorage}
import workflows4s.wio.WorkflowContext

import scala.annotation.nowarn

@nowarn
object DatabaseExample {

  // Database runtimes use IO effect type directly
  // Below shows the conceptual pattern - see full example in tests

  // doc_start
  // Define your workflow context
  trait MyCtx extends WorkflowContext {
    type State = String
    type Event = String
  }
  type Ctx = MyCtx

  val transactor: Transactor[IO]           = ???
  val storage: WorkflowStorage[IO, String] = ???
  val templateId                           = "my-workflow"

  // For actual usage, see workflows4s-doobie tests
  // DatabaseRuntime.create takes:
  // - workflow: WIO.Initial[IO, Ctx]
  // - initialState: WCState[Ctx]
  // - transactor: Transactor[IO]
  // - engine: WorkflowInstanceEngine[IO]
  // - eventCodec: ByteCodec[WCEvent[Ctx]]
  // - templateId: String

  // After creation:
  // val runtime: DatabaseRuntime[Ctx] = DatabaseRuntime.create(...)
  // val wfInstance: IO[WorkflowInstance[IO, WCState[Ctx]]] = runtime.createInstance("1")
  // doc_end

  {
    // doc_postgres_start
    given eventCodec: ByteCodec[String] = ???

    val postgresStorage: WorkflowStorage[IO, String] = new PostgresWorkflowStorage[String](transactor)
    // doc_postgres_end
  }

}
