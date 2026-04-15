package workflows4s.runtime.pekko

import cats.{Id, MonadThrow}
import cats.instances.future.catsStdInstancesForFuture
import com.typesafe.scalalogging.StrictLogging
import org.apache.pekko.actor.typed.*
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityRef, EntityTypeKey}
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.util.Timeout
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime
import workflows4s.runtime.instanceengine.WorkflowInstanceEngine
import workflows4s.runtime.registry.InMemoryWorkflowRegistry
import workflows4s.runtime.{DelegateWorkflowInstance, MappedWorkflowInstance, WorkflowInstance, WorkflowInstanceId}
import workflows4s.testing.{RecordingKnockerUpper, TestClock, TestRuntimeAdapter}
import workflows4s.wio.*

import java.time.Clock
import java.util.UUID
import scala.concurrent.{Await, Future}

class PekkoRuntimeAdapter[F[_]: MonadThrow, Ctx <: WorkflowContext](
    entityKeyPrefix: String,
    runToFuture: [A] => F[A] => Future[A],
)(using actorSystem: ActorSystem[?], ev: LiftWorkflowEffect[Ctx, F])
    extends TestRuntimeAdapter[F, Ctx]
    with StrictLogging {

  override protected val knockerUpper: RecordingKnockerUpper[F] = RecordingKnockerUpper[F]()
  override val clock: TestClock                                 = TestClock()
  override val registry: InMemoryWorkflowRegistry[F]            = InMemoryWorkflowRegistry[F](clock)
  override val engine: WorkflowInstanceEngine[F, Ctx]           =
    WorkflowInstanceEngine.default(knockerUpper, registry, clock)
  override def runSync[A](fa: F[A]): A                          = Await.result(runToFuture(fa), 3.seconds)

  val sharding = ClusterSharding(actorSystem)

  private val futureEngine: WorkflowInstanceEngine[Future, Ctx] = {
    given cats.Functor[Future] = catsStdInstancesForFuture(using actorSystem.executionContext)
    engine.mapK([A] => (fa: F[A]) => runToFuture(fa))
  }

  case class Stop(replyTo: ActorRef[Unit])

  type RawCmd = WorkflowBehavior.Command[Ctx]
  type Cmd    = WorkflowBehavior.Command[Ctx] | Stop

  override def runWorkflow(
      workflow: WIO.Initial[Ctx],
      state: WCState[Ctx],
  ): Actor = {
    // we create unique type key per workflow, so we can ensure we get right actor/behavior/input
    // with single shard region its tricky to inject input into behavior creation
    val typeKey = EntityTypeKey[Cmd](entityKeyPrefix + "-" + UUID.randomUUID().toString)

    // TODO we dont use PekkoRuntime because it's tricky to test recovery there.
    val _             = sharding.init(
      Entity(typeKey)(createBehavior = entityContext => {
        val persistenceId = PersistenceId(entityContext.entityTypeKey.name, entityContext.entityId)
        val instanceId    = WorkflowInstanceId(persistenceId.entityTypeHint, persistenceId.entityId)
        val base          = WorkflowBehavior(instanceId, persistenceId, workflow, state, futureEngine)
        Behaviors.intercept[Cmd, RawCmd](() =>
          new BehaviorInterceptor[Cmd, RawCmd]() {
            override def aroundReceive(
                ctx: TypedActorContext[Cmd],
                msg: Cmd,
                target: BehaviorInterceptor.ReceiveTarget[RawCmd],
            ): Behavior[RawCmd] =
              msg match {
                case Stop(replyTo) => Behaviors.stopped(() => replyTo ! ())
                case other         =>
                  // classtag-based filtering doesnt work here due to union type
                  // we are mimicking the logic of Interceptor where unhandled messaged are passed through with casting
                  target
                    .asInstanceOf[BehaviorInterceptor.ReceiveTarget[Any]](ctx, other)
                    .asInstanceOf[Behavior[RawCmd]]
              }
          },
        )(base)
      }),
    )
    val persistenceId = UUID.randomUUID().toString
    val entityRef     = sharding.entityRefFor(typeKey, persistenceId)
    Actor(entityRef, clock)
  }

  case class Actor(entityRef: EntityRef[Cmd], clock: Clock) extends DelegateWorkflowInstance[Id, WCState[Ctx]] {
    val base =
      PekkoWorkflowInstance(
        WorkflowInstanceId(entityRef.typeKey.name, entityRef.entityId),
        entityRef,
        queryTimeout = Timeout(3.seconds),
      )

    val delegate: WorkflowInstance[Id, WCState[Ctx]]                                                  = MappedWorkflowInstance(base, [t] => (x: Future[t]) => Await.result(x, 3.seconds))
    override def getExpectedSignals(includeRedeliverable: Boolean = false): Id[List[SignalDef[?, ?]]] =
      delegate.getExpectedSignals(includeRedeliverable)
  }

  override def recover(first: Actor): Actor = {
    given Timeout = Timeout(3.seconds)

    val isStopped = first.entityRef.ask(replyTo => Stop(replyTo))
    Await.result(isStopped, 3.seconds)
    Thread.sleep(100) // this is terrible, but sometimes akka gives us an already terminated actor if we ask for it too fast.
    val entityRef = sharding.entityRefFor(first.entityRef.typeKey, first.entityRef.entityId)
    logger.debug(s"""Original Actor: ${first.entityRef}
                    |New Actor     : ${entityRef}""".stripMargin)
    Actor(entityRef, first.clock)
  }
}
