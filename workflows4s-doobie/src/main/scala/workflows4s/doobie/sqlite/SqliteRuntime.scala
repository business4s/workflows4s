package workflows4s.doobie.sqlite

import java.nio.file.{Files, Path}
import java.time.Clock
import java.util.Properties
import cats.data.Kleisli
import cats.effect.{IO, LiftIO}
import doobie.implicits.*
import doobie.util.fragment.Fragment
import doobie.util.transactor.Transactor
import doobie.{ConnectionIO, WeakAsync}
import workflows4s.doobie.{ByteCodec, DbWorkflowInstance}
import workflows4s.runtime.registry.{NoOpWorkflowRegistry, WorkflowRegistry}
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
    workdir: Path,
    registryAgent: WorkflowRegistry.Agent[String],
) extends WorkflowRuntime[IO, Ctx, String] {

  private val storage = SqliteWorkflowStorage[WCEvent[Ctx]](eventCodec)

  override def createInstance(id: String): IO[WorkflowInstance[IO, State[Ctx]]] = {
    val dbPath = getDatabasePath(id)
    val xa     = createTransactor(dbPath)

    for {
      _ <- initSchema(xa)
    } yield {
      val base = new DbWorkflowInstance(
        (),                       // Storage doesn't need ID since each DB has one workflow
        ActiveWorkflow(workflow, initialState),
        storage,
        clock,
        knockerUpper.curried(id), // We still need the ID for the knocker upper
        registryAgent.curried(id), // And for the registry
      )

      new MappedWorkflowInstance(
        base,
        [t] =>
          (connIo: Kleisli[ConnectionIO, LiftIO[ConnectionIO], t]) =>
            WeakAsync.liftIO[ConnectionIO].use(liftIO => xa.trans.apply(connIo.apply(liftIO))),
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

    val properties = new Properties()
    // IMMEDIATE transaction mode ensures we get a write lock when we start a transaction
    properties.put("transaction_mode", "IMMEDIATE")

    Transactor.fromDriverManager[IO](
      driver = "org.sqlite.JDBC",
      url = dbUrl,
      info = properties,
      logHandler = None,
    )
  }

  private def initSchema(xa: Transactor[IO]): IO[Unit] = for {
    ddl <- IO.blocking(scala.io.Source.fromResource("schema/sqlite-schema.sql").mkString)
    _   <- Fragment.const(ddl).update.run.transact(xa).void
  } yield ()

}

object SqliteRuntime {
  def default[Ctx <: WorkflowContext](
      workflow: Initial[Ctx],
      initialState: WCState[Ctx],
      eventCodec: ByteCodec[WCEvent[Ctx]],
      knockerUpper: KnockerUpper.Agent[String],
      workdir: Path,
      clock: Clock = Clock.systemUTC(),
      registry: WorkflowRegistry.Agent[String] = NoOpWorkflowRegistry.Agent,
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
    )

  }
}
