package workflows4s.doobie.sqlite

import java.nio.file.Path
import java.time.Clock
import java.util.Properties

import cats.effect.IO
import doobie.util.transactor.Transactor
import doobie.{ConnectionIO, WeakAsync}
import workflows4s.doobie.{DbWorkflowInstance, EventCodec}
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.runtime.{MappedWorkflowInstance, WorkflowInstance, WorkflowRuntime}
import workflows4s.wio.WIO.Initial
import workflows4s.wio.{ActiveWorkflow, WCEvent, WCState, WorkflowContext}

class SqliteRuntime[Ctx <: WorkflowContext](
    workflow: Initial[Ctx],
    initialState: WCState[Ctx],
    clock: Clock,
    knockerUpper: KnockerUpper.Agent[String],
    eventCodec: EventCodec[WCEvent[Ctx]],
    dbFile: Path,
) extends WorkflowRuntime[IO, Ctx, String] {

  private val dbUrl: String = s"jdbc:sqlite:${dbFile.toString}"

  override def createInstance(id: String): IO[WorkflowInstance[IO, WCState[Ctx]]] = {
    val properties = new Properties

    properties.put("transaction_mode", "IMMEDIATE")

    val xa: Transactor[IO] = Transactor.fromDriverManager[IO](
      driver = "org.sqlite.JDBC",
      url = dbUrl,
      info = properties,
      logHandler = None,
    )

    WeakAsync
      .liftIO[ConnectionIO]
      .use { liftIo =>
        val base = new DbWorkflowInstance(
          id,
          ActiveWorkflow(workflow, initialState),
          new SqliteWorkflowStorage,
          liftIo,
          eventCodec,
          clock,
          knockerUpper,
        )
        IO(new MappedWorkflowInstance(base, xa.trans))
      }
  }
}

object SqliteRuntime {
  def default[Ctx <: WorkflowContext](
      workflow: Initial[Ctx],
      initialState: WCState[Ctx],
      eventCodec: EventCodec[WCEvent[Ctx]],
      knockerUpper: KnockerUpper.Agent[String],
      dbFile: Path,
      clock: Clock = Clock.systemUTC(),
  ): SqliteRuntime[Ctx] =
    new SqliteRuntime[Ctx](
      workflow = workflow,
      initialState = initialState,
      eventCodec = eventCodec,
      knockerUpper = knockerUpper,
      dbFile = dbFile,
      clock = clock,
    )
}
