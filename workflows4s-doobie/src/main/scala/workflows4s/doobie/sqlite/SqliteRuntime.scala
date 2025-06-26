package workflows4s.doobie.sqlite

import java.nio.file.Path
import java.time.Clock
import java.util.Properties

import cats.data.Kleisli
import cats.effect.{IO, LiftIO}
import doobie.implicits.*
import doobie.util.fragment.Fragment
import doobie.util.transactor.Transactor
import doobie.{ConnectionIO, WeakAsync}
import workflows4s.doobie.{ByteCodec, DbWorkflowInstance}
import workflows4s.runtime.registry.NoOpWorkflowRegistry
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.runtime.{MappedWorkflowInstance, WorkflowInstance, WorkflowRuntime}
import workflows4s.wio.WIO.Initial
import workflows4s.wio.WorkflowContext.State
import workflows4s.wio.{ActiveWorkflow, WCEvent, WCState, WorkflowContext}

class SqliteRuntime[Ctx <: WorkflowContext](
    workflow: Initial[Ctx],
    initialState: WCState[Ctx],
    clock: Clock,
    knockerUpper: KnockerUpper.Agent[String],
    eventCodec: ByteCodec[WCEvent[Ctx]],
    dbFile: Path,
) extends WorkflowRuntime[IO, Ctx, String] {

  val dbUrl: String = s"jdbc:sqlite:${dbFile.toString}"

  private val properties = {
    val props = Properties()
    props.put("transaction_mode", "IMMEDIATE")
    props
  }

  val xa: Transactor[IO] = Transactor.fromDriverManager[IO](
    driver = "org.sqlite.JDBC",
    url = dbUrl,
    info = properties,
    logHandler = None,
  )

  private def initSchema(): IO[Unit] = for {
    ddl <- IO.blocking(scala.io.Source.fromResource("schema/sqlite-schema.sql").mkString)
    _   <- Fragment.const(ddl).update.run.transact(xa).void
  } yield ()

  override def createInstance(id: String): IO[WorkflowInstance[IO, State[Ctx]]] = {
    initSchema() >> IO {
      given ByteCodec[WCEvent[Ctx]] = eventCodec
      val registryAgent             = NoOpWorkflowRegistry.Agent
      val base                      = new DbWorkflowInstance(
        id,
        ActiveWorkflow(workflow, initialState),
        SqliteWorkflowStorage[WCEvent[Ctx]](),
        clock,
        knockerUpper,
        registryAgent,
      )
      new MappedWorkflowInstance(
        base,
        [t] =>
          (connIo: Kleisli[ConnectionIO, LiftIO[ConnectionIO], t]) =>
            WeakAsync.liftIO[ConnectionIO].use(liftIO => xa.trans.apply(connIo.apply(liftIO))),
      )
    }
  }
}

object SqliteRuntime {
  def default[Ctx <: WorkflowContext](
      workflow: Initial[Ctx],
      initialState: WCState[Ctx],
      eventCodec: ByteCodec[WCEvent[Ctx]],
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
