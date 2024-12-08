package workflow4s.example

import _root_.doobie.ConnectionIO
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
import workflow4s.runtime.wakeup.KnockerUpper
import workflow4s.runtime.{InMemoryRuntime, InMemorySyncRuntime, WorkflowInstance}
import workflow4s.wio.*
import workflows4s.doobie.EventCodec
import workflows4s.doobie.postgres.{PostgresRuntime, WorkflowId}
import workflows4s.runtime.pekko.{PekkoRuntime, PekkoWorkflowInstance, WorkflowBehavior}

import java.time.Clock
import java.util.UUID
import scala.concurrent.{Await, Future}
import scala.util.Random

// Adapt various runtimes to a single interface for tests
trait TestRuntimeAdapter[Ctx <: WorkflowContext] {

  type Actor <: WorkflowInstance[Id, WCState[Ctx]]

  def runWorkflow[In](
      workflow: WIO[In, Nothing, WCState[Ctx], Ctx],
      input: In,
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
    override def runWorkflow[In](
        workflow: WIO[In, Nothing, WCState[Ctx], Ctx],
        input: In,
        state: WCState[Ctx],
        clock: Clock,
    ): Actor = Actor(workflow.provideInput(input), state, clock, List())

    override def recover(first: Actor): Actor = {
      Actor(first.workflow, first.state, first.clock, first.getEvents)
    }

    case class Actor(
        workflow: WIO[Any, Nothing, WCState[Ctx], Ctx],
        state: WCState[Ctx],
        clock: Clock,
        events: Seq[WCEvent[Ctx]],
    ) extends WorkflowInstance[Id, WCState[Ctx]]
        with EventIntrospection[WCEvent[Ctx]] {
      val base = {
        val runtime =
          new InMemorySyncRuntime[Ctx, Unit, Unit](workflow, _ => state, clock, KnockerUpper.noopFactory)(using IORuntime.global)
        val inst    = runtime.createInstance((), ())
        inst.recover(events)
        inst
      }

      override def queryState(): Id[WCState[Ctx]]                                                                                           = base.queryState()
      override def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): Id[Either[WorkflowInstance.UnexpectedSignal, Resp]] =
        base.deliverSignal(signalDef, req)
      override def wakeup(): Id[Unit]                                                                                                       = base.wakeup()
      override def getEvents: Seq[WCEvent[Ctx]]                                                                                             = base.getEvents
    }

  }

  case class InMemory[Ctx <: WorkflowContext]() extends TestRuntimeAdapter[Ctx] {
    override def runWorkflow[In](
        workflow: WIO[In, Nothing, WCState[Ctx], Ctx],
        input: In,
        state: WCState[Ctx],
        clock: Clock,
    ): Actor = {
      Actor(workflow.provideInput(input), state, clock, List())
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
        val runtime = new InMemoryRuntime[Ctx, Unit, Unit](workflow, _ => state, clock, KnockerUpper.noopFactory)
        val inst    = runtime.createInstance((), ()).unsafeRunSync()
        inst.recover(events).unsafeRunSync()
        inst
      }

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

    override def runWorkflow[In](
        workflow: WIO[In, Nothing, WCState[Ctx], Ctx],
        input: In,
        state: WCState[Ctx],
        clock: Clock,
    ): Actor = {
      import cats.effect.unsafe.implicits.global
      // we create unique type key per workflow, so we can ensure we get right actor/behavior/input
      // with single shard region its tricky to inject input into behavior creation
      val typeKey = EntityTypeKey[Cmd](entityKeyPrefix + "-" + UUID.randomUUID().toString)

      // we dont use PekkoRuntime because its tricky to test recover there.
      // TODO maybe we could use persistance test kit?
      val shardRegion   = sharding.init(
        Entity(typeKey)(createBehavior = entityContext => {
          val persistenceId = PersistenceId(entityContext.entityTypeKey.name, entityContext.entityId)
          val base          = WorkflowBehavior(persistenceId, workflow.provideInput(input), state, clock)
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
      Actor(entityRef)
    }

    case class Actor(entityRef: EntityRef[Cmd]) extends WorkflowInstance[Id, WCState[Ctx]] {
      val base                                                                                                                              = PekkoWorkflowInstance(entityRef, stateQueryTimeout = Timeout(1.second))
      override def queryState(): Id[WCState[Ctx]]                                                                                           = base.queryState().await
      override def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): Id[Either[WorkflowInstance.UnexpectedSignal, Resp]] = {
        val resp = base.deliverSignal(signalDef, req).await
        wakeup()
        resp
      }

      override def wakeup(): Id[Unit] = base.wakeup().await

      // TODO could at least use futureValue from scalatest
      extension [T](f: Future[T]) {
        def await: T = Await.result(f, 5.seconds)
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
      Actor(entityRef)
    }
  }

  class Postgres[Ctx <: WorkflowContext](xa: Transactor[IO], eventCodec: EventCodec[WCEvent[Ctx]]) extends TestRuntimeAdapter[Ctx] {
    import _root_.doobie.implicits.*

    override def runWorkflow[In](
        workflow: WIO[In, Nothing, WCState[Ctx], Ctx],
        input: In,
        state: WCState[Ctx],
        clock: Clock,
    ): Actor = {
      val runtime =
        PostgresRuntime.defaultWithState[Ctx, Unit](workflow.provideInput(input), _ => state, eventCodec, xa, KnockerUpper.noopFactory, clock)
      Actor(runtime.createInstance(WorkflowId(Random.nextLong()), ()))
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
    }

  }

}
