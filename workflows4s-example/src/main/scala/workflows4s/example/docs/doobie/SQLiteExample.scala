package workflows4s.example.docs.doobie

import cats.effect.IO
import workflows4s.doobie.ByteCodec
import workflows4s.doobie.sqlite.SqliteRuntime
import workflows4s.example.docs.{MyEventBase, MyState}
import workflows4s.runtime.WorkflowInstance
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.wio.{WCState, WorkflowContext}

import java.nio.file.Path
import scala.annotation.nowarn

@nowarn("msg=unused local definition")
object SQLiteExample {

  import cats.effect.unsafe.implicits.global
  import workflows4s.example.docs.Context.*
  {
    // sqlite_start
    val workflow: WIO.Initial                    = ???
    val initialState: MyState                    = ???
    val knockerUpper: KnockerUpper.Agent[String] = ???
    val eventCodec: ByteCodec[MyEventBase]       = ???
    val workdir: Path                            = ??? // Directory where database files will be created

    val runtime: SqliteRuntime[Ctx]                        = SqliteRuntime.default(workflow, initialState, eventCodec, knockerUpper, workdir).unsafeRunSync()
    val wfInstance: IO[WorkflowInstance[IO, WCState[Ctx]]] = runtime.createInstance("my-instance-1")
    // sqlite_end
  }

}
