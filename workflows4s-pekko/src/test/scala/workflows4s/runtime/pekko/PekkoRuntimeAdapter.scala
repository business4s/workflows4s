package workflows4s.runtime.pekko

import cats.Id
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.typesafe.scalalogging.StrictLogging
import org.apache.pekko.actor.typed.*
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityRef, EntityTypeKey}
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.util.Timeout
import workflows4s.runtime.{MappedWorkflowInstance, WorkflowInstance, WorkflowInstanceId}
import workflows4s.testing.{IOTestRuntimeAdapter, TestRuntimeAdapter}
import workflows4s.wio.*

import java.time.Clock
import java.util.UUID
import scala.concurrent.Await
import scala.concurrent.duration.*

/** IO-based Pekko runtime adapter for concurrency tests that need raw IO operations.
  */
class PekkoRuntimeAdapter[Ctx <: WorkflowContext](entityKeyPrefix: String)(implicit actorSystem: ActorSystem[?])
    extends IOTestRuntimeAdapter[Ctx]
    with StrictLogging {

  /** Pekko actor messaging is slower than in-memory, so use a longer timeout. */
  override def testTimeout: FiniteDuration = 60.seconds

  val sharding = ClusterSharding(actorSystem)

  case class Stop(replyTo: ActorRef[Unit])

  type RawCmd = WorkflowBehavior.Command[Ctx]
  type Cmd    = WorkflowBehavior.Command[Ctx] | Stop

  override type Actor = IOActor

  override def runWorkflow(
      workflow: WIO.Initial[IO, Ctx],
      state: WCState[Ctx],
  ): Actor = {
    val (entityRef, typeKey) = createEntityRef(workflow, state)
    IOActor(entityRef, typeKey, clock)
  }

  override def recover(first: Actor): Actor = {
    given Timeout = Timeout(3.seconds)

    val isStopped = first.entityRef.ask(replyTo => Stop(replyTo))
    Await.result(isStopped, 3.seconds)
    Thread.sleep(100) // this is terrible, but sometimes akka gives us an already terminated actor if we ask for it too fast.
    val entityRef = sharding.entityRefFor(first.typeKey, first.entityRef.entityId)
    logger.debug(s"""Original Actor: ${first.entityRef}
                    |New Actor     : ${entityRef}""".stripMargin)
    IOActor(entityRef, first.typeKey, first.actorClock)
  }

  protected def createEntityRef(
      workflow: WIO.Initial[IO, Ctx],
      state: WCState[Ctx],
  ): (EntityRef[Cmd], EntityTypeKey[Cmd]) = {
    val typeKey = EntityTypeKey[Cmd](entityKeyPrefix + "-" + UUID.randomUUID().toString)

    val _             = sharding.init(
      Entity(typeKey)(createBehavior = entityContext => {
        val persistenceId = PersistenceId(entityContext.entityTypeKey.name, entityContext.entityId)
        val instanceId    = WorkflowInstanceId(persistenceId.entityTypeHint, persistenceId.entityId)
        val base          = WorkflowBehavior(instanceId, persistenceId, workflow, state, engine)
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
    (entityRef, typeKey)
  }

  case class IOActor(entityRef: EntityRef[Cmd], typeKey: EntityTypeKey[Cmd], actorClock: Clock) extends WorkflowInstance[IO, WCState[Ctx]] {
    private val base =
      PekkoWorkflowInstance(
        WorkflowInstanceId(entityRef.typeKey.name, entityRef.entityId),
        entityRef,
        queryTimeout = Timeout(3.seconds),
      )

    override def id: WorkflowInstanceId                                                                                                   = base.id
    override def queryState(): IO[WCState[Ctx]]                                                                                           = base.queryState()
    override def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): IO[Either[WorkflowInstance.UnexpectedSignal, Resp]] =
      base.deliverSignal(signalDef, req)
    override def wakeup(): IO[Unit]                                                                                                       = base.wakeup()
    override def getProgress: IO[model.WIOExecutionProgress[WCState[Ctx]]]                                                                = base.getProgress
    override def getExpectedSignals: IO[List[SignalDef[?, ?]]]                                                                            = base.getExpectedSignals

    def toIdActor: WorkflowInstance[Id, WCState[Ctx]] = MappedWorkflowInstance(this, [t] => (x: IO[t]) => x.unsafeRunSync())
  }
}

/** Id-based Pekko runtime adapter for tests that expect synchronous execution.
  */
class PekkoTestRuntimeAdapter[Ctx <: WorkflowContext](entityKeyPrefix: String)(implicit actorSystem: ActorSystem[?])
    extends TestRuntimeAdapter[Ctx]
    with StrictLogging {

  val sharding = ClusterSharding(actorSystem)

  case class Stop(replyTo: ActorRef[Unit])

  type RawCmd = WorkflowBehavior.Command[Ctx]
  type Cmd    = WorkflowBehavior.Command[Ctx] | Stop

  override type Actor = WorkflowInstance[Id, WCState[Ctx]]

  override def runWorkflow(
      workflow: WIO.Initial[IO, Ctx],
      state: WCState[Ctx],
  ): Actor = {
    val (entityRef, typeKey) = createEntityRef(workflow, state)
    IOActor(entityRef, typeKey, clock).toIdActor
  }

  override def recover(first: Actor): Actor = {
    given Timeout = Timeout(3.seconds)

    val ioActor   = first match {
      case mapped: MappedWorkflowInstance[IO, Id, WCState[Ctx]] @unchecked =>
        mapped.base.asInstanceOf[IOActor]
      case _                                                               =>
        throw new IllegalStateException("Expected MappedWorkflowInstance wrapping IOActor")
    }
    val isStopped = ioActor.entityRef.ask(replyTo => Stop(replyTo))
    Await.result(isStopped, 3.seconds)
    Thread.sleep(100) // this is terrible, but sometimes akka gives us an already terminated actor if we ask for it too fast.
    val entityRef = sharding.entityRefFor(ioActor.typeKey, ioActor.entityRef.entityId)
    logger.debug(s"""Original Actor: ${ioActor.entityRef}
                    |New Actor     : ${entityRef}""".stripMargin)
    IOActor(entityRef, ioActor.typeKey, ioActor.actorClock).toIdActor
  }

  protected def createEntityRef(
      workflow: WIO.Initial[IO, Ctx],
      state: WCState[Ctx],
  ): (EntityRef[Cmd], EntityTypeKey[Cmd]) = {
    val typeKey = EntityTypeKey[Cmd](entityKeyPrefix + "-" + UUID.randomUUID().toString)

    val _             = sharding.init(
      Entity(typeKey)(createBehavior = entityContext => {
        val persistenceId = PersistenceId(entityContext.entityTypeKey.name, entityContext.entityId)
        val instanceId    = WorkflowInstanceId(persistenceId.entityTypeHint, persistenceId.entityId)
        val base          = WorkflowBehavior(instanceId, persistenceId, workflow, state, engine)
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
    (entityRef, typeKey)
  }

  case class IOActor(entityRef: EntityRef[Cmd], typeKey: EntityTypeKey[Cmd], actorClock: Clock) extends WorkflowInstance[IO, WCState[Ctx]] {
    private val base =
      PekkoWorkflowInstance(
        WorkflowInstanceId(entityRef.typeKey.name, entityRef.entityId),
        entityRef,
        queryTimeout = Timeout(3.seconds),
      )

    override def id: WorkflowInstanceId                                                                                                   = base.id
    override def queryState(): IO[WCState[Ctx]]                                                                                           = base.queryState()
    override def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): IO[Either[WorkflowInstance.UnexpectedSignal, Resp]] =
      base.deliverSignal(signalDef, req)
    override def wakeup(): IO[Unit]                                                                                                       = base.wakeup()
    override def getProgress: IO[model.WIOExecutionProgress[WCState[Ctx]]]                                                                = base.getProgress
    override def getExpectedSignals: IO[List[SignalDef[?, ?]]]                                                                            = base.getExpectedSignals

    def toIdActor: WorkflowInstance[Id, WCState[Ctx]] = MappedWorkflowInstance(this, [t] => (x: IO[t]) => x.unsafeRunSync())
  }
}
