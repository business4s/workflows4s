package workflows4s.example.docs.doobie

import workflows4s.wio.WorkflowContext

import java.nio.file.Path
import scala.annotation.nowarn

@nowarn
object SQLiteExample {

  // SQLite runtime requires a specific internal effect type (Result)
  // The workflow context and engine must use this internal type
  // Below shows the conceptual pattern - see full example in tests

  // sqlite_start
  // Define your workflow context - the runtime handles effect type internally
  trait MyCtx extends WorkflowContext {
    type State = String
    type Event = String
  }
  type Ctx = MyCtx

  val workdir: Path = ??? // Directory where database files will be created

  // For actual usage, see workflows4s-doobie tests
  // The SqliteRuntime.create method takes:
  // - workflow: WIO.Initial using the internal Result type
  // - initialState: WCState[Ctx]
  // - eventCodec: ByteCodec[WCEvent[Ctx]]
  // - engine: WorkflowInstanceEngine using the internal Result type
  // - workdir: Path

  // After creation:
  // val runtime: SqliteRuntime[Ctx] = SqliteRuntime.create(...).unsafeRunSync()
  // val wfInstance: IO[WorkflowInstance[IO, WCState[Ctx]]] = runtime.createInstance("my-instance-1")
  // sqlite_end

}
