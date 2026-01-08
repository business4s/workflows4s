package workflows4s.runtime.pekko

import com.typesafe.scalalogging.StrictLogging
import org.apache.pekko.actor.typed.*
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityRef, EntityTypeKey}
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.util.Timeout
import workflows4s.runtime.{DelegateWorkflowInstance, WorkflowInstance, WorkflowInstanceId}
import workflows4s.runtime.instanceengine.{Effect, FutureEffect, LazyFuture}
import workflows4s.runtime.pekko.PekkoRuntimeAdapter.Stop
import workflows4s.testing.{EventIntrospection, WorkflowTestAdapter}
import workflows4s.wio.*

import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

object PekkoRuntimeAdapter {
  case class Stop(replyTo: ActorRef[Unit])
}

/** Pekko runtime adapter for polymorphic tests, specifically implemented for LazyFuture.
  */
class PekkoRuntimeAdapter[Ctx <: WorkflowContext](entityKeyPrefix: String)(implicit actorSystem: ActorSystem[?])
    extends WorkflowTestAdapter[LazyFuture, Ctx]
    with StrictLogging {

  implicit val ec: ExecutionContext       = actorSystem.executionContext
  implicit def effect: Effect[LazyFuture] = FutureEffect.futureEffect

  /** Pekko messaging is slower than in-memory; override the default timeout. */
  override def testTimeout: FiniteDuration = 60.seconds

  // --- Pekko Specifics ---
  private val sharding = ClusterSharding(actorSystem)

  type RawCmd = WorkflowBehavior.Command[Ctx]
  type Cmd    = RawCmd | Stop

  /** Actor implementation that delegates to the Pekko-backed instance */
  case class PekkoTestActor(
      entityRef: EntityRef[Cmd],
      typeKey: EntityTypeKey[Cmd],
      override val id: WorkflowInstanceId,
  ) extends DelegateWorkflowInstance[LazyFuture, WCState[Ctx]]
      with EventIntrospection[WCEvent[Ctx]] {

    // The actual Pekko-specific instance implementation
    override val delegate: WorkflowInstance[LazyFuture, WCState[Ctx]] =
      PekkoWorkflowInstance(
        id,
        entityRef,
        queryTimeout = Timeout(10.seconds),
      )

    // Pekko actors are event-sourced, but getting events for introspection
    // usually requires a query-side or a specific test command.
    override def getEvents: Seq[WCEvent[Ctx]] = {
      // In a real scenario, you'd use Pekko Persistence Query here.
      // For this adapter, we assume the test logic primarily uses queryState.
      Nil
    }

    override def getExpectedSignals: LazyFuture[List[SignalDef[?, ?]]] = delegate.getExpectedSignals
  }

  override type Actor = PekkoTestActor

  override def runWorkflow(
      workflow: WIO.Initial[LazyFuture, Ctx],
      state: WCState[Ctx],
  ): Actor = {
    val (entityRef, typeKey) = createEntityRef(workflow, state)
    val instId               = WorkflowInstanceId(typeKey.name, entityRef.entityId)
    PekkoTestActor(entityRef, typeKey, instId)
  }

  override def recover(first: Actor): Actor = {
    given Timeout = Timeout(5.seconds)

    // 1. Tell the current actor to stop
    val isStopped = LazyFuture.fromFuture(first.entityRef.ask[Unit](replyTo => Stop(replyTo)))
    effect.runSyncUnsafe(isStopped)

    // 2. Brief wait to allow the ShardRegion to realize the actor is gone
    Thread.sleep(200)

    // 3. Obtain a fresh EntityRef for the same ID.
    // When we interact with it, Pekko Persistence will recover the state.
    val newEntityRef = sharding.entityRefFor(first.typeKey, first.entityRef.entityId)

    logger.debug(s"Recovered actor for ID: ${first.id.instanceId}")
    PekkoTestActor(newEntityRef, first.typeKey, first.id)
  }

  protected def createEntityRef(
      workflow: WIO.Initial[LazyFuture, Ctx],
      state: WCState[Ctx],
  ): (EntityRef[Cmd], EntityTypeKey[Cmd]) = {
    // Use a stable string for the type key
    val typeKeyName = s"$entityKeyPrefix-${UUID.randomUUID().toString}"
    val typeKey     = EntityTypeKey[Cmd](typeKeyName)

    sharding.init(
      Entity(typeKey) { entityContext =>
        val persistenceId = PersistenceId(entityContext.entityTypeKey.name, entityContext.entityId)
        val instanceId    = WorkflowInstanceId(persistenceId.entityTypeHint, persistenceId.entityId)
        val base          = WorkflowBehavior(instanceId, persistenceId, workflow, state, engine)

        Behaviors.intercept[Cmd, RawCmd](() =>
          new BehaviorInterceptor[Cmd, RawCmd]() {
            override def aroundReceive(
                ctx: TypedActorContext[Cmd],
                msg: Cmd,
                target: BehaviorInterceptor.ReceiveTarget[RawCmd],
            ): Behavior[RawCmd] = {
              (msg: Any) match {
                case s: Stop                  =>
                  Behaviors.stopped(() => s.replyTo ! ())
                case other: RawCmd @unchecked =>
                  target(ctx, other)
                case internal                 =>
                  // Pass internal Pekko messages (RecoveryPermitGranted) through untouched
                  target
                    .asInstanceOf[BehaviorInterceptor.ReceiveTarget[Any]](ctx, internal)
                    .asInstanceOf[Behavior[RawCmd]]
              }
            }
          },
        )(base)
      },
    )

    val entityId  = UUID.randomUUID().toString
    val entityRef = sharding.entityRefFor(typeKey, entityId)
    (entityRef, typeKey)
  }
}
