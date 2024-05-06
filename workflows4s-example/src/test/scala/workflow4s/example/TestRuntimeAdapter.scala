package workflow4s.example

import cats.Id
import cats.implicits.catsSyntaxOptionId
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import org.apache.pekko.persistence.jdbc.query.scaladsl.JdbcReadJournal
import org.apache.pekko.persistence.query.PersistenceQuery
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.stream.scaladsl.Sink
import org.scalatest.concurrent.Futures
import org.scalatest.concurrent.Futures.{PatienceConfig, scaled}
import org.scalatest.concurrent.ScalaFutures.convertScalaFuture
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime
import workflow4s.example.TestRuntimeAdapter.TestRuntime
import workflow4s.runtime.{InMemoryRuntime, InMemorySyncRuntime, RunningWorkflow}
import workflow4s.wio.*
import workflows4s.runtime.pekko.{PekkoRunningWorkflow, WorkflowBehavior}

import java.time.Clock
import java.util.UUID
import scala.concurrent.{Await, Future}

// Adapt various runtimes to a signle interface for the purpose of tests
trait TestRuntimeAdapter {
  def runWorkflow[Ctx <: WorkflowContext, In](
      workflow: WIO[In, Nothing, WCState[Ctx], Ctx],
      input: In,
      state: WCState[Ctx],
      clock: Clock,
      events: Seq[WCEvent[Ctx]],
  ): TestRuntime[Ctx]

}

object TestRuntimeAdapter {

  type TestRuntime[Ctx <: WorkflowContext] = RunningWorkflow[Id, WCState[Ctx]] & EventIntrospection[WCEvent[Ctx]]

  trait EventIntrospection[Event] {
    def getEvents: Seq[Event]
  }

  object InMemorySync extends TestRuntimeAdapter {
    override def runWorkflow[Ctx <: WorkflowContext, In](
        workflow: WIO[In, Nothing, WCState[Ctx], Ctx],
        input: In,
        state: WCState[Ctx],
        clock: Clock,
        events: Seq[WCEvent[Ctx]],
    ): TestRuntime[Ctx] = {
      import cats.effect.unsafe.implicits.global
      val base = InMemorySyncRuntime.createWithState(workflow, input, state, clock, events)
      new RunningWorkflow[Id, WCState[Ctx]] with EventIntrospection[WCEvent[Ctx]] {
        override def queryState(): Id[WCState[Ctx]]                                                                                          = base.queryState()
        override def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): Id[Either[RunningWorkflow.UnexpectedSignal, Resp]] =
          base.deliverSignal(signalDef, req)
        override def wakeup(): Id[Unit]                                                                                                      = base.wakeup()
        override def getEvents: Seq[WCEvent[Ctx]]                                                                                            = base.getEvents
      }
    }
  }

  object InMemory extends TestRuntimeAdapter {
    override def runWorkflow[Ctx <: WorkflowContext, In](
        workflow: WIO[In, Nothing, WCState[Ctx], Ctx],
        input: In,
        state: WCState[Ctx],
        clock: Clock,
        events: Seq[WCEvent[Ctx]],
    ): TestRuntime[Ctx] = {
      import cats.effect.unsafe.implicits.global
      val base = InMemoryRuntime.runWorkflowWithState(workflow, input, state, events, clock).unsafeRunSync()
      new RunningWorkflow[Id, WCState[Ctx]] with EventIntrospection[WCEvent[Ctx]] {
        override def queryState(): Id[WCState[Ctx]]                                                                                          = base.queryState().unsafeRunSync()
        override def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): Id[Either[RunningWorkflow.UnexpectedSignal, Resp]] =
          base.deliverSignal(signalDef, req).unsafeRunSync()
        override def wakeup(): Id[Unit]                                                                                                      = base.wakeup().unsafeRunSync()
        override def getEvents: Seq[WCEvent[Ctx]]                                                                                            = base.getEvents.unsafeRunSync()
      }
    }
  }

  class Pekko(entityKeyPrefix: String)(implicit actorSystem: ActorSystem[_]) extends TestRuntimeAdapter {
    override def runWorkflow[Ctx <: WorkflowContext, In](
        workflow: WIO[In, Nothing, WCState[Ctx], Ctx],
        input: In,
        state: WCState[Ctx],
        clock: Clock,
        events: Seq[WCEvent[Ctx]],
    ): TestRuntime[Ctx] = {
      import cats.effect.unsafe.implicits.global
      // we create unique type key per workflow, so we can ensure we get right actor/behavior/input
      // with single shard region its tricky to inject input into behavior creation
      val typeKey       = EntityTypeKey[WorkflowBehavior.Command[Ctx]](entityKeyPrefix + "-" + UUID.randomUUID().toString)
      val sharding      = ClusterSharding(actorSystem)
      val shardRegion   = sharding.init(
        Entity(typeKey)(createBehavior = entityContext => {
          val persistenceId = PersistenceId(entityContext.entityTypeKey.name, entityContext.entityId)
          WorkflowBehavior.withInput(persistenceId, workflow, state, input, clock)
        }),
      )
      val persistenceId = UUID.randomUUID().toString
      val entityRef     = sharding.entityRefFor(typeKey, persistenceId)
      val base          = PekkoRunningWorkflow(entityRef)
      val journal       = PersistenceQuery(actorSystem).readJournalFor[JdbcReadJournal](JdbcReadJournal.Identifier)

      new RunningWorkflow[Id, WCState[Ctx]] with EventIntrospection[WCEvent[Ctx]] {
        override def queryState(): Id[WCState[Ctx]]                                                                                          = base.queryState().await
        override def deliverSignal[Req, Resp](signalDef: SignalDef[Req, Resp], req: Req): Id[Either[RunningWorkflow.UnexpectedSignal, Resp]] =
          base.deliverSignal(signalDef, req).await
        override def wakeup(): Id[Unit]                                                                                                      = base.wakeup().await
        override def getEvents: Seq[WCEvent[Ctx]]                                                                                            = journal
          .eventsByPersistenceId(persistenceId, 0L, Long.MaxValue)
          .runWith(Sink.seq)
          .await
          .flatMap(e =>
            e.event match {
              case WorkflowBehavior.CommandAccepted => None
              case x: WCEvent[Ctx]                  => Some(x) // TODO unchecked match, can be mitigated through classtag
              case _                                => ???
            },
          )

        // TODO could at least use futureValue from scalatest
        implicit class AwaitOps[T](f: Future[T]) {
          def await: T = Await.result(f, 5.seconds)
        }
      }
    }
  }

}
