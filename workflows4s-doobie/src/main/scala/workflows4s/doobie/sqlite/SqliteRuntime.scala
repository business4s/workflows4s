package workflows4s.doobie.sqlite

import cats.arrow.FunctionK
import cats.data.Kleisli
import cats.effect.Async
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import com.typesafe.scalalogging.StrictLogging
import doobie.implicits.*
import doobie.util.fragment.Fragment
import doobie.util.transactor.Transactor
import doobie.{ConnectionIO, WeakAsync}
import workflows4s.doobie.{ByteCodec, DbWorkflowInstance}
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.runtime.{MappedWorkflowInstance, WorkflowInstance, WorkflowInstanceId, WorkflowRuntime}
import workflows4s.wio.WIO.Initial
import workflows4s.wio.WorkflowContext.State
import workflows4s.wio.{ActiveWorkflow, WCEvent, WCState, WorkflowContext}

import java.nio.file.{Files, Path}
import java.util.Properties

/** Runtime using one SQLite database file per workflow instance. Auto-initializes the schema on first access. Suitable for embedded/single-node
  * deployments without an external database.
  */
class SqliteRuntime[F[_]: Async, Ctx <: WorkflowContext](
    val workflow: Initial[Ctx],
    initialState: WCState[Ctx],
    engine: WorkflowInstanceEngine[F, Ctx],
    eventCodec: ByteCodec[WCEvent[Ctx]],
    workdir: Path,
    val templateId: String,
) extends WorkflowRuntime[F, Ctx]
    with StrictLogging {
  private val storage = SqliteWorkflowStorage[WCEvent[Ctx]](eventCodec)

  override def createInstance(id: String): F[WorkflowInstance[F, State[Ctx]]] = {
    val dbPath = getDatabasePath(id)
    val xa     = createTransactor(dbPath)
    initSchema(xa, dbPath).map { _ =>
      val instanceId = WorkflowInstanceId(templateId, id)
      val base       = new DbWorkflowInstance[F, Ctx](
        instanceId,
        ActiveWorkflow(instanceId, workflow, initialState),
        storage,
        engine,
      )
      new MappedWorkflowInstance(
        base,
        [t] =>
          (connIo: Kleisli[ConnectionIO, FunctionK[F, ConnectionIO], t]) =>
            // we use rawTrans because locking manages transactions itself.
            // And querying events without locking doesn't require any kind of transaction.
            WeakAsync.liftK[F, ConnectionIO].use(fk => xa.rawTrans.apply(connIo.apply(fk))),
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

  private def createTransactor(dbPath: Path): Transactor[F] = {
    val dbUrl = s"jdbc:sqlite:${dbPath.toAbsolutePath}"

    Transactor.fromDriverManager[F](
      driver = "org.sqlite.JDBC",
      url = dbUrl,
      info = new Properties(),
      logHandler = None,
    )
  }

  private def initSchema(xa: Transactor[F], dbPath: Path): F[Unit] = {
    val F = Async[F]
    for {
      dbExists <- F.blocking(Files.exists(dbPath))
      _        <- if !dbExists then {
                    for {
                      _   <- F.delay(logger.info(s"Initializing DB at ${dbPath}"))
                      ddl <- F.blocking(scala.util.Using.resource(scala.io.Source.fromResource("schema/sqlite-schema.sql"))(_.mkString))
                      _   <- Fragment.const(ddl).update.run.transact(xa).void
                    } yield ()
                  } else F.unit
    } yield ()
  }

}

object SqliteRuntime {
  def create[F[_]: Async, Ctx <: WorkflowContext](
      workflow: Initial[Ctx],
      initialState: WCState[Ctx],
      eventCodec: ByteCodec[WCEvent[Ctx]],
      engine: WorkflowInstanceEngine[F, Ctx],
      workdir: Path,
      templateId: String = s"sqlite-runtime-${java.util.UUID.randomUUID().toString.take(8)}",
  ): F[SqliteRuntime[F, Ctx]] = {
    Async[F].blocking(Files.createDirectories(workdir)).map { _ =>
      new SqliteRuntime[F, Ctx](
        workflow = workflow,
        initialState = initialState,
        eventCodec = eventCodec,
        engine = engine,
        workdir = workdir,
        templateId = templateId,
      )
    }
  }
}
