package workflows4s.doobie.sqlite

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

trait PathCodec[Id] {
  def encode(id: Id): String
  def decode(path: String): Id
}

object PathCodec {
  implicit val stringPathCodec: PathCodec[String] = new PathCodec[String] {
    def encode(id: String): String   = id
    def decode(path: String): String = path
  }
}

class SqliteRuntime[WorkflowId <: String: PathCodec, Ctx <: WorkflowContext](
    workflow: Initial[Ctx],
    initialState: WCState[Ctx],
    clock: Clock,
    knockerUpper: KnockerUpper.Agent[WorkflowId],
    eventCodec: EventCodec[WCEvent[Ctx]],
    path: String,
) extends WorkflowRuntime[IO, Ctx, WorkflowId] {

  private val workflowId: WorkflowId = implicitly[PathCodec[WorkflowId]].decode(path)
  private val dbUrl: String          = s"jdbc:sqlite:$path"

  override def createInstance(id: WorkflowId): IO[WorkflowInstance[IO, WCState[Ctx]]] = {
    val properties         = new Properties
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
          SqliteWorkflowStorage[WorkflowId],
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
  def default[Ctx <: WorkflowContext, WorkflowId <: String: PathCodec, Input](
      workflow: Initial[Ctx],
      initialState: WCState[Ctx],
      eventCodec: EventCodec[WCEvent[Ctx]],
      knockerUpper: KnockerUpper.Agent[WorkflowId],
      path: String,
      clock: Clock = Clock.systemUTC(),
  ) =
    new SqliteRuntime(
      workflow = workflow,
      initialState = initialState,
      eventCodec = eventCodec,
      knockerUpper = knockerUpper,
      path = path,
      clock = clock,
    )
}
