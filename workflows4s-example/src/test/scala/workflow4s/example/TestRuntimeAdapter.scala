package workflow4s.example

import cats.Id
import cats.effect.IO
import com.typesafe.scalalogging.StrictLogging
import doobie.ConnectionIO
import doobie.util.transactor.Transactor
import org.apache.pekko.actor.typed.*
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.ShardingEnvelope
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityRef, EntityTypeKey}
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.util.Timeout
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime
import workflow4s.runtime.{InMemoryRuntime, InMemorySyncRuntime, RunningWorkflow}
import workflow4s.wio.*
import workflows4s.doobie.EventCodec
import workflows4s.doobie.postgres.{PostgresRuntime, WorkflowId}
import workflows4s.runtime.pekko.{PekkoRunningWorkflow, WorkflowBehavior}

import java.time.Clock
import java.util.UUID
import scala.concurrent.{Await, Future}
import scala.util.Random

// Adapt various runtimes to a single interface for tests
trait TestRuntimeAdapter[Ctx <: WorkflowContext] {

  type Actor <: RunningWorkflow[Id, WCState[Ctx]]

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
    ) extends RunningWorkflow[Id, WCState[Ctx]]
        with EventIntrospection[WCEvent[Ctx]] {
      val base = {
        import cats.effect.unsafe.implicits.global
        InMemorySyncRuntime.runWorkflow(workflow, state, clock, events)
      }

      override def queryState(): Id[WCState[Ctx]]                                                                                          = base.queryState()
      override def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): Id[Either[RunningWorkflow.UnexpectedSignal, Resp]] =
        base.deliverSignal(signalDef, req)
      override def wakeup(): Id[Unit]                                                                                                      = base.wakeup()
      override def getEvents: Seq[WCEvent[Ctx]]                                                                                            = base.getEvents
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
    ) extends RunningWorkflow[Id, WCState[Ctx]]
        with EventIntrospection[WCEvent[Ctx]] {
      import cats.effect.unsafe.implicits.global
      val base = InMemoryRuntime.runWorkflow(workflow, state, events, clock).unsafeRunSync()

      override def queryState(): Id[WCState[Ctx]]                                                                                          = base.queryState().unsafeRunSync()
      override def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): Id[Either[RunningWorkflow.UnexpectedSignal, Resp]] =
        base.deliverSignal(signalDef, req).unsafeRunSync()
      override def wakeup(): Id[Unit]                                                                                                      = base.wakeup().unsafeRunSync()
      override def getEvents: Seq[WCEvent[Ctx]]                                                                                            = base.getEvents.unsafeRunSync()
    }

  }

  class Pekko[Ctx <: WorkflowContext](entityKeyPrefix: String)(implicit actorSystem: ActorSystem[?]) extends TestRuntimeAdapter[Ctx] with StrictLogging {

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

      val shardRegion   = sharding.init(
        Entity(typeKey)(createBehavior = entityContext => {
          val persistenceId = PersistenceId(entityContext.entityTypeKey.name, entityContext.entityId)
          val base          = WorkflowBehavior.withInput(persistenceId, workflow, state, input, clock)
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

    case class Actor(entityRef: EntityRef[Cmd]) extends RunningWorkflow[Id, WCState[Ctx]] {
      val base                                                                                                                             = PekkoRunningWorkflow(entityRef, stateQueryTimeout = Timeout(1.second))
      override def queryState(): Id[WCState[Ctx]]                                                                                          = base.queryState().await
      override def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): Id[Either[RunningWorkflow.UnexpectedSignal, Resp]] = {
        val resp = base.deliverSignal(signalDef, req).await
        wakeup()
        resp
      }

      override def wakeup(): Id[Unit] = base.wakeup().await

      // TODO could at least use futureValue from scalatest
      implicit class AwaitOps[T](f: Future[T]) {
        def await: T = Await.result(f, 5.seconds)
      }
    }

    override def recover(first: Actor): Actor = {
      implicit val timeout: Timeout = Timeout(1.second)
      val isStopped                 = first.entityRef.ask(replyTo => Stop(replyTo))
      Await.result(isStopped, 1.second)
      Thread.sleep(100) // this is terrible but sometimes akka gives us already terminated actor if we ask for it too fast.
      val entityRef = sharding.entityRefFor(first.entityRef.typeKey, first.entityRef.entityId)
      logger.debug(s"""Original Actor: ${first.entityRef}
                      |New Actor     : ${entityRef}""".stripMargin)
      Actor(entityRef)
    }
  }

  class Postgres[Ctx <: WorkflowContext](xa: Transactor[IO], eventCodec: EventCodec[WCEvent[Ctx]]) extends TestRuntimeAdapter[Ctx] {
    import doobie.implicits.*


    override def runWorkflow[In](
        workflow: WIO[In, Nothing, WCState[Ctx], Ctx],
        input: In,
        state: WCState[Ctx],
        clock: Clock,
    ): Actor = {
      val runtime = PostgresRuntime(_ => KnockerUpper.noop, clock)
      Actor(runtime.runWorkflow(WorkflowId(Random.nextLong()), workflow.provideInput(input), state, eventCodec))
    }

    override def recover(first: Actor): Actor = {
      first // in this runtime there is no in-memory state, hence no recovery.
    }

    case class Actor(base: IO[RunningWorkflow[ConnectionIO, WCState[Ctx]]]
    ) extends RunningWorkflow[Id, WCState[Ctx]] {
      import cats.effect.unsafe.implicits.global

      override def queryState(): WCState[Ctx] = base.flatMap(_.queryState().transact(xa)).unsafeRunSync()

      override def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): Either[RunningWorkflow.UnexpectedSignal, Resp] =
        base.flatMap(_.deliverSignal(signalDef, req).transact(xa)).unsafeRunSync()

      override def wakeup(): Id[Unit] = base.flatMap(_.wakeup().transact(xa)).unsafeRunSync()
    }

  }

}
