package workflows4s.doobie.sqlite

import java.nio.file.{Files, Path}
import java.time.Clock
import java.util.Properties
import cats.data.Kleisli
import cats.effect.{IO, LiftIO}
import com.typesafe.scalalogging.StrictLogging
import doobie.implicits.*
import doobie.util.fragment.Fragment
import doobie.util.transactor.Transactor
import doobie.{ConnectionIO, WeakAsync}
import workflows4s.doobie.{ByteCodec, DbWorkflowInstance}
import workflows4s.runtime.registry.{NoOpWorkflowRegistry, WorkflowRegistry}
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.runtime.{MappedWorkflowInstance, WorkflowInstance, WorkflowInstanceId, WorkflowRuntime}
import workflows4s.wio.WIO.Initial
import workflows4s.wio.WorkflowContext.State
import workflows4s.wio.{ActiveWorkflow, WCEvent, WCState, WorkflowContext}

class SqliteRuntime[Ctx <: WorkflowContext](
    workflow: Initial[Ctx],
    initialState: WCState[Ctx],
    clock: Clock,
    knockerUpper: KnockerUpper.Agent,
    eventCodec: ByteCodec[WCEvent[Ctx]],
    workdir: Path,
    registryAgent: WorkflowRegistry.Agent,
    val runtimeId: String,
) extends WorkflowRuntime[IO, Ctx]
    with StrictLogging {

  private val storage = SqliteWorkflowStorage[WCEvent[Ctx]](eventCodec)

  override def createInstance(id: String): IO[WorkflowInstance[IO, State[Ctx]]] = {
    val dbPath = getDatabasePath(id)
    val xa     = createTransactor(dbPath)
    for {
      _ <- initSchema(xa, dbPath)
    } yield {
      val base = new DbWorkflowInstance(
        WorkflowInstanceId(runtimeId, id), // Storage doesn't need ID since each DB has one workflow
        ActiveWorkflow(workflow, initialState),
        storage,
        clock,
        knockerUpper,
        registryAgent,
      )

      new MappedWorkflowInstance(
        base,
        [t] =>
          (connIo: Kleisli[ConnectionIO, LiftIO[ConnectionIO], t]) =>
            // we use rawTrans because locking manages transactions itself.
            // And querying events without locking doesn't require any kind of transaction.
            WeakAsync.liftIO[ConnectionIO].use(liftIO => xa.rawTrans.apply(connIo.apply(liftIO))),
      )
    }
  }

  private def sanitizeWorkflowId(id: String): String = {
    // Replace characters that might be problematic in filenames
    id.replaceAll("[^a-zA-Z0-9._-]", "_")
  }

  private def getDatabasePath(workflowId: String): Path = {
    // TODO, two workflow ids might clash due to sanitization. For now its good enough but maybe we should hash instead?
    workdir.resolve(s"${sanitizeWorkflowId(workflowId)}.db")
  }

  private def createTransactor(dbPath: Path): Transactor[IO] = {
    val dbUrl = s"jdbc:sqlite:${dbPath.toAbsolutePath}"

    Transactor.fromDriverManager[IO](
      driver = "org.sqlite.JDBC",
      url = dbUrl,
      info = new Properties(),
      logHandler = None,
    )
  }

  private def initSchema(xa: Transactor[IO], dbPath: Path): IO[Unit] = for {
    dbExists <- IO(Files.exists(dbPath))
    _        <- if !dbExists then {
                  for {
                    _   <- IO(logger.info(s"Initializing DB at ${dbPath}"))
                    ddl <- IO.blocking(scala.io.Source.fromResource("schema/sqlite-schema.sql").mkString)
                    _   <- Fragment.const(ddl).update.run.transact(xa).void
                  } yield ()
                } else IO.unit
  } yield ()

}

object SqliteRuntime {
  def default[Ctx <: WorkflowContext](
      workflow: Initial[Ctx],
      initialState: WCState[Ctx],
      eventCodec: ByteCodec[WCEvent[Ctx]],
      knockerUpper: KnockerUpper.Agent,
      workdir: Path,
      clock: Clock = Clock.systemUTC(),
      registry: WorkflowRegistry.Agent = NoOpWorkflowRegistry.Agent,
      runtimeId: String = s"sqlite-runtime-${java.util.UUID.randomUUID().toString.take(8)}",
  ): IO[SqliteRuntime[Ctx]] = {

    for {
      _ <- IO(Files.createDirectories(workdir))
    } yield new SqliteRuntime[Ctx](
      workflow = workflow,
      initialState = initialState,
      eventCodec = eventCodec,
      knockerUpper = knockerUpper,
      workdir = workdir,
      clock = clock,
      registryAgent = registry,
      runtimeId = runtimeId,
    )

  }
}
