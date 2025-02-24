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
}

class SqliteRuntime[WorkflowId <: String: PathCodec, Ctx <: WorkflowContext](
    workflow: Initial[Ctx],
    initialState: WCState[Ctx],
    clock: Clock,
    knockerUpper: KnockerUpper.Agent[WorkflowId],
    eventCodec: EventCodec[WCEvent[Ctx]],
) extends WorkflowRuntime[IO, Ctx, WorkflowId] {

  override def createInstance(id: WorkflowId): IO[WorkflowInstance[IO, WCState[Ctx]]] = {
    // FIXME: find a way to create JDBC driver with transanction and path
    val properties         = new Properties
    properties.put("transanction_mode", "IMMEDIATE")
    val xa: Transactor[IO] = Transactor.fromDriverManager[IO](
      driver = "org.sqlite.JDBC",
      url = "jdbc:sqlite:sample.db",
      info = properties,
      logHandler = None,
    )
    WeakAsync
      .liftIO[ConnectionIO]
      .use(liftIo =>
        IO {
          val base = new DbWorkflowInstance(
            id,
            ActiveWorkflow(workflow, initialState),
            SqliteWorkflowStorage[WorkflowId],
            liftIo,
            eventCodec,
            clock,
            knockerUpper,
          )
          new MappedWorkflowInstance(base, xa.trans)
        },
      )
  }
}

object SqliteRuntime {
  def default[Ctx <: WorkflowContext, WorkflowId <: String: PathCodec, Input](
      workflow: Initial[Ctx],
      initialState: WCState[Ctx],
      eventCodec: EventCodec[WCEvent[Ctx]],
      knockerUpper: KnockerUpper.Agent[WorkflowId],
      clock: Clock = Clock.systemUTC(),
  ) =
    new SqliteRuntime(workflow = workflow, initialState = initialState, eventCodec = eventCodec, knockerUpper = knockerUpper, clock = clock)
}
