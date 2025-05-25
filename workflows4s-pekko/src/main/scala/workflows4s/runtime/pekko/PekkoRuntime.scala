package workflows4s.runtime.pekko

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import org.apache.pekko.persistence.typed.PersistenceId
import workflows4s.runtime.registry.{NoOpWorkflowRegistry, WorkflowRegistry}
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.runtime.{WorkflowInstance, WorkflowRuntime}
import workflows4s.wio.WIO.Initial
import workflows4s.wio.{WCState, WorkflowContext}

import java.time.Clock
import scala.concurrent.Future

trait PekkoRuntime[Ctx <: WorkflowContext] extends WorkflowRuntime[Future, Ctx, PekkoRuntime.WorkflowId] {
  def createInstance_(id: String): WorkflowInstance[Future, WCState[Ctx]]
  def initializeShard(): Unit
}

class PekkoRuntimeImpl[Ctx <: WorkflowContext](
    workflow: Initial[Ctx],
    initialState: WCState[Ctx],
    entityName: String,
    clock: Clock,
    knockerUpper: KnockerUpper.Agent[PekkoRuntime.WorkflowId],
    registry: WorkflowRegistry.Agent[PekkoRuntime.WorkflowId],
)(using
    system: ActorSystem[?],
) extends PekkoRuntime[Ctx] {
  private val sharding: ClusterSharding = ClusterSharding(system)
  private type Command = WorkflowBehavior.Command[Ctx]
  private val typeKey = EntityTypeKey[Command](entityName)

  override def createInstance(id: String): Future[WorkflowInstance[Future, WCState[Ctx]]] = {
    Future.successful(createInstance_(id))
  }
  override def createInstance_(id: String): WorkflowInstance[Future, WCState[Ctx]]        = {
    PekkoWorkflowInstance(sharding.entityRefFor(typeKey, id), knockerUpper.curried(id), clock, registry.curried(id))
  }

  def initializeShard(): Unit = {
    val _ = sharding.init(
      Entity(typeKey)(createBehavior = entityContext => {
        val persistenceId = PersistenceId(entityContext.entityTypeKey.name, entityContext.entityId)
        WorkflowBehavior(persistenceId, workflow, initialState, clock)
      }),
    )
    ()
  }

}

object PekkoRuntime {

  type WorkflowId = String

  def create[Ctx <: WorkflowContext](
      entityName: String,
      workflow: Initial[Ctx],
      initialState: WCState[Ctx],
      knockerUpper: KnockerUpper.Agent[WorkflowId],
      clock: Clock = Clock.systemUTC(),
      registry: WorkflowRegistry.Agent[WorkflowId] = NoOpWorkflowRegistry.Agent,
  )(using
      system: ActorSystem[?],
  ): PekkoRuntime[Ctx] = {
    new PekkoRuntimeImpl(workflow, initialState, entityName, clock, knockerUpper, registry)
  }

}
