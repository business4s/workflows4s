package workflows4s.example

import _root_.doobie.util.transactor.Transactor
import cats.Id
import cats.effect.IO
import cats.effect.unsafe.IORuntime
import com.typesafe.scalalogging.StrictLogging
import org.apache.pekko.actor.typed.*
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityRef, EntityTypeKey}
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.util.Timeout
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime
import workflows4s.doobie.{ByteCodec, DatabaseRuntime}
import workflows4s.doobie.postgres.{PostgresWorkflowStorage, WorkflowId}
import workflows4s.runtime.pekko.{PekkoWorkflowInstance, WorkflowBehavior}
import workflows4s.runtime.wakeup.NoOpKnockerUpper
import workflows4s.runtime.{InMemoryRuntime, InMemorySyncRuntime, InMemorySyncWorkflowInstance, WorkflowInstance}
import workflows4s.wio.*
import workflows4s.wio.model.WIOExecutionProgress

import java.time.Clock
import java.util.UUID
import scala.concurrent.{Await, Future}
import scala.util.Random

// Adapt various runtimes to a single interface for tests
trait TestRuntimeAdapter[Ctx <: WorkflowContext] {

  type Actor <: WorkflowInstance[Id, WCState[Ctx]]

  def runWorkflow(
      workflow: WIO[Any, Nothing, WCState[Ctx], Ctx],
      state: WCState[Ctx],
      clock: Clock,
  ): Actor

  def recover(first: Actor): Actor

}

object TestRuntimeAdapter {

  trait EventIntrospection[Event] {
    def getEvents: Seq[Event]
  }

  case class InMemorySync[Ctx <: WorkflowContext]() extends TestRuntimeAdapter[Ctx] {
    override def runWorkflow(
        workflow: WIO.Initial[Ctx],
        state: WCState[Ctx],
        clock: Clock,
    ): Actor = Actor(workflow, state, clock, List())

    override def recover(first: Actor): Actor = {
      Actor(first.initialWorkflow, first.state, first.clock, first.getEvents)
    }

    case class Actor(
        initialWorkflow: WIO.Initial[Ctx],
        state: WCState[Ctx],
        clock: Clock,
        events: Seq[WCEvent[Ctx]],
    ) extends WorkflowInstance[Id, WCState[Ctx]]
        with EventIntrospection[WCEvent[Ctx]] {
      val base: InMemorySyncWorkflowInstance[Ctx] = {
        val runtime =
          new InMemorySyncRuntime[Ctx, Unit](initialWorkflow, state, clock, NoOpKnockerUpper.Agent)(using IORuntime.global)
        val inst    = runtime.createInstance(())
        inst.recover(events)
        inst
      }

      override def getProgress: Id[WIOExecutionProgress[WCState[Ctx]]]                                                                      = base.getProgress
      override def queryState(): Id[WCState[Ctx]]                                                                                           = base.queryState()
      override def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): Id[Either[WorkflowInstance.UnexpectedSignal, Resp]] =
        base.deliverSignal(signalDef, req)
      override def wakeup(): Id[Unit]                                                                                                       = base.wakeup()
      override def getEvents: Seq[WCEvent[Ctx]]                                                                                             = base.getEvents
    }

  }

  case class InMemory[Ctx <: WorkflowContext]() extends TestRuntimeAdapter[Ctx] {
    override def runWorkflow(
        workflow: WIO.Initial[Ctx],
        state: WCState[Ctx],
        clock: Clock,
    ): Actor = {
      Actor(workflow, state, clock, List())
    }

    override def recover(first: Actor): Actor =
      Actor(first.workflow, first.state, first.clock, first.getEvents)

    case class Actor(
        workflow: WIO[Any, Nothing, WCState[Ctx], Ctx],
        state: WCState[Ctx],
        clock: Clock,
        events: Seq[WCEvent[Ctx]],
    ) extends WorkflowInstance[Id, WCState[Ctx]]
        with EventIntrospection[WCEvent[Ctx]] {
      import cats.effect.unsafe.implicits.global
      val base = {
        val runtime = new InMemoryRuntime[Ctx, Unit](workflow, state, clock, NoOpKnockerUpper.Agent)
        val inst    = runtime.createInstance(()).unsafeRunSync()
        inst.recover(events).unsafeRunSync()
        inst
      }

      override def getProgress: Id[WIOExecutionProgress[WCState[Ctx]]]                                                                      = base.getProgress.unsafeRunSync()
      override def queryState(): Id[WCState[Ctx]]                                                                                           = base.queryState().unsafeRunSync()
      override def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): Id[Either[WorkflowInstance.UnexpectedSignal, Resp]] =
        base.deliverSignal(signalDef, req).unsafeRunSync()
      override def wakeup(): Id[Unit]                                                                                                       = base.wakeup().unsafeRunSync()
      override def getEvents: Seq[WCEvent[Ctx]]                                                                                             = base.getEvents.unsafeRunSync()
    }

  }

  class Pekko[Ctx <: WorkflowContext](entityKeyPrefix: String)(implicit actorSystem: ActorSystem[?])
      extends TestRuntimeAdapter[Ctx]
      with StrictLogging {

    val sharding = ClusterSharding(actorSystem)

    case class Stop(replyTo: ActorRef[Unit])
    type RawCmd = WorkflowBehavior.Command[Ctx]
    type Cmd    = WorkflowBehavior.Command[Ctx] | Stop

    override def runWorkflow(
        workflow: WIO.Initial[Ctx],
        state: WCState[Ctx],
        clock: Clock,
    ): Actor = {
      import cats.effect.unsafe.implicits.global
      // we create unique type key per workflow, so we can ensure we get right actor/behavior/input
      // with single shard region its tricky to inject input into behavior creation
      val typeKey = EntityTypeKey[Cmd](entityKeyPrefix + "-" + UUID.randomUUID().toString)

      // we dont use PekkoRuntime because it's tricky to test recovery there.
      val _             = sharding.init(
        Entity(typeKey)(createBehavior = entityContext => {
          val persistenceId = PersistenceId(entityContext.entityTypeKey.name, entityContext.entityId)
          val base          = WorkflowBehavior(persistenceId, workflow, state, clock)
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

    case class Actor(entityRef: EntityRef[Cmd], clock: Clock) extends WorkflowInstance[Id, WCState[Ctx]] {
      val base                                                                                                                              =
        PekkoWorkflowInstance(entityRef, NoOpKnockerUpper.Agent, clock, stateQueryTimeout = Timeout(1.second))
      override def queryState(): Id[WCState[Ctx]]                                                                                           = base.queryState().await
      override def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): Id[Either[WorkflowInstance.UnexpectedSignal, Resp]] = {
        val resp = base.deliverSignal(signalDef, req).await
        wakeup()
        resp
      }

      override def getProgress: Id[WIOExecutionProgress[WCState[Ctx]]] = base.getProgress.await
      override def wakeup(): Id[Unit]                                  = base.wakeup().await

      extension [T](f: Future[T]) {
        def await: T = Await.result(f, 2.seconds)
      }
    }

    override def recover(first: Actor): Actor = {
      given Timeout = Timeout(1.second)
      val isStopped = first.entityRef.ask(replyTo => Stop(replyTo))
      Await.result(isStopped, 1.second)
      Thread.sleep(100) // this is terrible but sometimes akka gives us already terminated actor if we ask for it too fast.
      val entityRef = sharding.entityRefFor(first.entityRef.typeKey, first.entityRef.entityId)
      logger.debug(s"""Original Actor: ${first.entityRef}
                      |New Actor     : ${entityRef}""".stripMargin)
      Actor(entityRef, first.clock)
    }
  }

  class Postgres[Ctx <: WorkflowContext](xa: Transactor[IO], eventCodec: ByteCodec[WCEvent[Ctx]]) extends TestRuntimeAdapter[Ctx] {

    override def runWorkflow(
        workflow: WIO.Initial[Ctx],
        state: WCState[Ctx],
        clock: Clock,
    ): Actor = {
      val storage = PostgresWorkflowStorage()(using eventCodec)
      val runtime =
        DatabaseRuntime.default[Ctx, WorkflowId](workflow, state, xa, NoOpKnockerUpper.Agent, storage, clock)
      Actor(runtime.createInstance(WorkflowId(Random.nextLong())))
    }

    override def recover(first: Actor): Actor = {
      first // in this runtime there is no in-memory state, hence no recovery.
    }

    case class Actor(base: IO[WorkflowInstance[IO, WCState[Ctx]]]) extends WorkflowInstance[Id, WCState[Ctx]] {
      import cats.effect.unsafe.implicits.global

      override def queryState(): WCState[Ctx] = base.flatMap(_.queryState()).unsafeRunSync()

      override def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): Either[WorkflowInstance.UnexpectedSignal, Resp] =
        base.flatMap(_.deliverSignal(signalDef, req)).unsafeRunSync()

      override def wakeup(): Id[Unit] = base.flatMap(_.wakeup()).unsafeRunSync()

      override def getProgress: Id[WIOExecutionProgress[WCState[Ctx]]] = base.flatMap(_.getProgress).unsafeRunSync()
    }

  }

}
