package workflows4s.runtime.pekko

import cats.effect.unsafe.IORuntime
import org.apache.pekko.actor.typed.ActorSystem
import workflow4s.runtime.{WorkflowInstance, WorkflowRuntime}
import workflow4s.wio.{WCState, WorkflowContext}
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityContext, EntityTypeKey}
import org.apache.pekko.persistence.typed.PersistenceId
import workflow4s.wio.WIO.Initial

import java.time.Clock
import scala.concurrent.Future

trait PekkoRuntime[Ctx <: WorkflowContext] extends WorkflowRuntime[Future, Ctx, String, Unit] {
  def createInstance(id: String): WorkflowInstance[Future, WCState[Ctx]]
}

class PekkoRuntimeImpl[Ctx <: WorkflowContext, Input <: WCState[Ctx]](
    workflow: Initial[Ctx, Input],
    initialState: EntityContext[?] => Input,
    entityName: String,
    clock: Clock,
)(implicit
    system: ActorSystem[Any],
    IORuntime: IORuntime,
) extends PekkoRuntime[Ctx] {
  private val sharding: ClusterSharding = ClusterSharding(system)
  private type Command = WorkflowBehavior.Command[Ctx]
  private val typeKey = EntityTypeKey[Command](entityName)

  override def createInstance(id: String, in: Unit): Future[WorkflowInstance[Future, WCState[Ctx]]] = {
    Future.successful(createInstance(id))
  }
  override def createInstance(id: String): WorkflowInstance[Future, WCState[Ctx]]                   = {
    PekkoWorkflowInstance(sharding.entityRefFor(typeKey, id))
  }

  def initializeShard(): Unit = {
    sharding.init(
      Entity(typeKey)(createBehavior = entityContext => {
        val persistenceId = PersistenceId(entityContext.entityTypeKey.name, entityContext.entityId)
        val input         = initialState(entityContext)
        WorkflowBehavior(persistenceId, workflow.provideInput(input), input, clock)
      }),
    )
  }

}

object PekkoRuntime {

  def create[Ctx <: WorkflowContext, Input <: WCState[Ctx]](
      entityName: String,
      workflow: Initial[Ctx, Input],
      initialState: EntityContext[?] => Input,
      clock: Clock = Clock.systemUTC(),
  )(implicit
      ioRuntime: IORuntime,
      system: ActorSystem[Any],
  ): PekkoRuntime[Ctx] = {
    new PekkoRuntimeImpl(workflow, initialState, entityName, clock)
  }

}