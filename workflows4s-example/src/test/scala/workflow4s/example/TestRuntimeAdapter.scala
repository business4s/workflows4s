package workflow4s.example

import cats.Id
import com.typesafe.scalalogging.StrictLogging
import org.apache.pekko.actor.typed.*
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.ShardingEnvelope
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityRef, EntityTypeKey}
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.util.Timeout
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime
import workflow4s.runtime.{InMemoryRuntime, InMemorySyncRuntime, RunningWorkflow}
import workflow4s.wio.*
import workflows4s.runtime.pekko.{PekkoRunningWorkflow, WorkflowBehavior}

import java.time.Clock
import java.util.UUID
import scala.concurrent.{Await, Future}

// Adapt various runtimes to a signle interface for the purpose of tests
trait TestRuntimeAdapter {

  type Actor[Ctx <: WorkflowContext] <: RunningWorkflow[Id, WCState[Ctx]]

  def runWorkflow[Ctx <: WorkflowContext, In](
      workflow: WIO[In, Nothing, WCState[Ctx], Ctx],
      input: In,
      state: WCState[Ctx],
      clock: Clock,
  ): Actor[Ctx]

  def recover[Ctx <: WorkflowContext](first: Actor[Ctx]): Actor[Ctx]

}

object TestRuntimeAdapter {

  trait EventIntrospection[Event] {
    def getEvents: Seq[Event]
  }

  object InMemorySync extends TestRuntimeAdapter {
    override def runWorkflow[Ctx <: WorkflowContext, In](
        workflow: WIO[In, Nothing, WCState[Ctx], Ctx],
        input: In,
        state: WCState[Ctx],
        clock: Clock,
    ): Actor[Ctx] = Actor(workflow.provideInput(input), state, clock, List())

    override def recover[Ctx <: WorkflowContext](first: Actor[Ctx]): Actor[Ctx] = {
      Actor(first.workflow, first.state, first.clock, first.getEvents)
    }

    case class Actor[Ctx <: WorkflowContext](
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

  object InMemory extends TestRuntimeAdapter {
    override def runWorkflow[Ctx <: WorkflowContext, In](
        workflow: WIO[In, Nothing, WCState[Ctx], Ctx],
        input: In,
        state: WCState[Ctx],
        clock: Clock,
    ): Actor[Ctx] = {
      Actor(workflow.provideInput(input), state, clock, List())
    }

    override def recover[Ctx <: WorkflowContext](first: Actor[Ctx]): Actor[Ctx] =
      Actor(first.workflow, first.state, first.clock, first.getEvents)

    case class Actor[Ctx <: WorkflowContext](
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

  class Pekko(entityKeyPrefix: String)(implicit actorSystem: ActorSystem[_]) extends TestRuntimeAdapter with StrictLogging {

    val sharding = ClusterSharding(actorSystem)

    case class Stop(replyTo: ActorRef[Unit])
    type RawCmd[Ctx <: WorkflowContext] = WorkflowBehavior.Command[Ctx]
    type Cmd[Ctx <: WorkflowContext]    = WorkflowBehavior.Command[Ctx] | Stop

    override def runWorkflow[Ctx <: WorkflowContext, In](
        workflow: WIO[In, Nothing, WCState[Ctx], Ctx],
        input: In,
        state: WCState[Ctx],
        clock: Clock,
    ): Actor[Ctx] = {
      import cats.effect.unsafe.implicits.global
      // we create unique type key per workflow, so we can ensure we get right actor/behavior/input
      // with single shard region its tricky to inject input into behavior creation
      val typeKey  = EntityTypeKey[Cmd[Ctx]](entityKeyPrefix + "-" + UUID.randomUUID().toString)

      val shardRegion   = sharding.init(
        Entity(typeKey)(createBehavior = entityContext => {
          val persistenceId = PersistenceId(entityContext.entityTypeKey.name, entityContext.entityId)
          val base          = WorkflowBehavior.withInput(persistenceId, workflow, state, input, clock)
          Behaviors.intercept[Cmd[Ctx], RawCmd[Ctx]](() =>
            new BehaviorInterceptor[Cmd[Ctx], RawCmd[Ctx]]() {
              override def aroundReceive(
                  ctx: TypedActorContext[Cmd[Ctx]],
                  msg: Cmd[Ctx],
                  target: BehaviorInterceptor.ReceiveTarget[RawCmd[Ctx]],
              ): Behavior[RawCmd[Ctx]] =
                msg match {
                  case Stop(replyTo)               => Behaviors.stopped(() => replyTo ! ())
                  case other =>
                    // classtag-based filtering doesnt work here due to union type
                    // we are mimicking the logic of Interceptor where unhandled messaged are passed through with casting
                    target.asInstanceOf[BehaviorInterceptor.ReceiveTarget[Any]](ctx, other)
                    .asInstanceOf[Behavior[RawCmd[Ctx]]]
                }
            },
          )(base)
        })
      )
      val persistenceId = UUID.randomUUID().toString
      val entityRef     = sharding.entityRefFor(typeKey, persistenceId)
      Actor(entityRef)
    }

    case class Actor[Ctx <: WorkflowContext](entityRef: EntityRef[Cmd[Ctx]]) extends RunningWorkflow[Id, WCState[Ctx]] {
      val base          = PekkoRunningWorkflow(entityRef, stateQueryTimeout = Timeout(1.second))
      override def queryState(): Id[WCState[Ctx]]                                                                                          = base.queryState().await
      override def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): Id[Either[RunningWorkflow.UnexpectedSignal, Resp]] = {
        val resp = base.deliverSignal(signalDef, req).await
        wakeup()
        resp
      }

      override def wakeup(): Id[Unit]                                                                                                      = base.wakeup().await

      // TODO could at least use futureValue from scalatest
      implicit class AwaitOps[T](f: Future[T]) {
        def await: T = Await.result(f, 5.seconds)
      }
    }

    override def recover[Ctx <: WorkflowContext](first: Actor[Ctx]): Actor[Ctx] = {
      implicit val timeout: Timeout = Timeout(1.second)
      val isStopped = first.entityRef.ask(replyTo => Stop(replyTo))
      Await.result(isStopped, 1.second)
      Thread.sleep(100) // this is terrible but sometimes akka gives us already terminated actor if we ask for it too fast.
      val entityRef = sharding.entityRefFor(first.entityRef.typeKey, first.entityRef.entityId)
      logger.debug(
        s"""Original Actor: ${first.entityRef}
           |New Actor     : ${entityRef}""".stripMargin)
      Actor(entityRef)
    }
  }

}
