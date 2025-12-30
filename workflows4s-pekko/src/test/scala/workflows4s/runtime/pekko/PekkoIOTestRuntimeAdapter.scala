package workflows4s.runtime.pekko

import cats.effect.IO
import com.typesafe.scalalogging.StrictLogging
import org.apache.pekko.actor.typed.*
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityRef, EntityTypeKey}
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.util.Timeout
import workflows4s.runtime.{MappedWorkflowInstance, WorkflowInstance, WorkflowInstanceId}
import workflows4s.testing.IOTestRuntimeAdapter
import workflows4s.wio.*

import java.time.Clock
import java.util.UUID
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*

/** Pekko runtime adapter for IO-based tests. Uses Future-based PekkoWorkflowInstance internally and converts to IO for test compatibility.
  */
class PekkoIOTestRuntimeAdapter[Ctx <: WorkflowContext](entityKeyPrefix: String)(implicit actorSystem: ActorSystem[?])
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
    // we create unique type key per workflow, so we can ensure we get right actor/behavior/input
    // with single shard region its tricky to inject input into behavior creation
    val typeKey = EntityTypeKey[Cmd](entityKeyPrefix + "-" + UUID.randomUUID().toString)

    // TODO we dont use PekkoRuntime because it's tricky to test recovery there.
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
    (entityRef, typeKey)
  }

  /** IO-based actor wrapper that delegates to PekkoWorkflowInstance (Future) and converts to IO for test compatibility.
    */
  case class IOActor(entityRef: EntityRef[Cmd], typeKey: EntityTypeKey[Cmd], actorClock: Clock) extends WorkflowInstance[IO, WCState[Ctx]] {
    private val delegate: WorkflowInstance[IO, WCState[Ctx]] = {
      val futureBase: WorkflowInstance[Future, WCState[Ctx]] =
        PekkoWorkflowInstance(
          WorkflowInstanceId(entityRef.typeKey.name, entityRef.entityId),
          entityRef,
          queryTimeout = Timeout(3.seconds),
        )
      // Convert Future to IO
      MappedWorkflowInstance(futureBase, [t] => (x: Future[t]) => IO.fromFuture(IO(x)))
    }

    override def id: WorkflowInstanceId = delegate.id

    override def queryState(): IO[WCState[Ctx]] = delegate.queryState()

    override def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): IO[Either[WorkflowInstance.UnexpectedSignal, Resp]] =
      delegate.deliverSignal(signalDef, req)

    override def wakeup(): IO[Unit] = delegate.wakeup()

    override def getProgress: IO[model.WIOExecutionProgress[WCState[Ctx]]] = delegate.getProgress

    override def getExpectedSignals: IO[List[SignalDef[?, ?]]] = delegate.getExpectedSignals
  }
}
