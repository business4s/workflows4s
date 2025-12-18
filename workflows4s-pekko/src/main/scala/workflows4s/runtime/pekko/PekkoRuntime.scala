package workflows4s.runtime.pekko

import cats.effect.IO
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import org.apache.pekko.persistence.typed.PersistenceId
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.runtime.{WorkflowInstance, WorkflowInstanceId, WorkflowRuntime}
import workflows4s.wio.WIO.Initial
import workflows4s.wio.{WCState, WorkflowContext}

trait PekkoRuntime[Ctx <: WorkflowContext] extends WorkflowRuntime[IO, Ctx] {
  def createInstance_(id: String): WorkflowInstance[IO, WCState[Ctx]]
  def initializeShard(): Unit
}

class PekkoRuntimeImpl[Ctx <: WorkflowContext](
    val workflow: Initial[IO, Ctx],
    initialState: WCState[Ctx],
    entityName: String,
    engine: WorkflowInstanceEngine[IO],
    val templateId: String,
)(using system: ActorSystem[?])
    extends PekkoRuntime[Ctx] {
  private val sharding: ClusterSharding = ClusterSharding(system)
  private type Command = WorkflowBehavior.Command[Ctx]
  private val typeKey = EntityTypeKey[Command](entityName)

  override def createInstance(id: String): IO[WorkflowInstance[IO, WCState[Ctx]]] = {
    IO.pure(createInstance_(id))
  }
  override def createInstance_(id: String): WorkflowInstance[IO, WCState[Ctx]]    = {
    val instanceId = WorkflowInstanceId(templateId, id)
    PekkoWorkflowInstance(instanceId, sharding.entityRefFor(typeKey, id))
  }

  def initializeShard(): Unit = {
    val _ = sharding.init(
      Entity(typeKey)(createBehavior = entityContext => {
        val persistenceId = PersistenceId(entityContext.entityTypeKey.name, entityContext.entityId)
        val instanceId    = WorkflowInstanceId(templateId, persistenceId.entityId)
        WorkflowBehavior(instanceId, persistenceId, workflow, initialState, engine)
      }),
    )
    ()
  }

}

object PekkoRuntime {

  type WorkflowId = String

  def create[Ctx <: WorkflowContext](
      entityName: String,
      workflow: Initial[IO, Ctx],
      initialState: WCState[Ctx],
      engine: WorkflowInstanceEngine[IO],
  )(using
      system: ActorSystem[?],
  ): PekkoRuntime[Ctx] = {
    // this might need customization if you have two clusters with the same entities but workflows from both in the same knocker-upper/registry.
    val templateId = s"pekko-runtime-$entityName}"
    new PekkoRuntimeImpl(workflow, initialState, entityName, engine, templateId)
  }

}
