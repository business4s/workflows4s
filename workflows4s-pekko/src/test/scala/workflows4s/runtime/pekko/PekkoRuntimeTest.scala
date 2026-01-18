package workflows4s.runtime.pekko

import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, ScalaTestWithActorTestKit}
import org.apache.pekko.persistence.jdbc.testkit.scaladsl.SchemaUtils
import workflows4s.runtime.instanceengine.{Effect, FutureEffect, LazyFuture}
import workflows4s.testing.{GenericTestCtx, WorkflowRuntimeTest}

import scala.concurrent.{Await, ExecutionContext}
import org.apache.pekko.cluster.typed.Cluster
import org.apache.pekko.cluster.typed.Join
import scala.concurrent.duration.*

class PekkoRuntimeTest extends ScalaTestWithActorTestKit(ActorTestKit("PekkoRuntimeTest")) with WorkflowRuntimeTest[LazyFuture] {

  given ExecutionContext = testKit.system.executionContext

  override given effect: Effect[LazyFuture] = FutureEffect.futureEffect

  override def unsafeRun(program: => LazyFuture[Unit]): Unit = {
    effect.runSyncUnsafe(program)
  }

  override def beforeAll(): Unit = {
    super.beforeAll()
    // Initialize JDBC schema for Pekko Persistence before actors start
    Await.result(SchemaUtils.createIfNotExists()(using testKit.system), 10.seconds)

    val cluster = Cluster(testKit.system)
    cluster.manager ! Join(cluster.selfMember.address)

    eventually(org.scalatest.concurrent.Futures.timeout(5.seconds)) {
      assert(cluster.selfMember.status == org.apache.pekko.cluster.MemberStatus.Up)
    }
    ()
  }

  lazy val pekkoAdapter = new PekkoRuntimeAdapter[ctx.type]("pekko-test")(using testKit.system)

  "Pekko Runtime (Future)" - {

    workflowTests(pekkoAdapter)

    "should handle recovery specifically" in {
      val utils      = new TestUtils
      val (id, step) = utils.runCustom(LazyFuture.successful(()))

      val actor = pekkoAdapter.runWorkflow(
        step.provideInput(GenericTestCtx.State.empty),
        GenericTestCtx.State.empty,
      )

      effect.runSyncUnsafe(actor.wakeup())
      val stateBefore = effect.runSyncUnsafe(actor.queryState())

      // Test the recovery logic specific to the Pekko adapter
      val recoveredActor = pekkoAdapter.recover(actor)
      val stateAfter     = effect.runSyncUnsafe(recoveredActor.queryState())

      assert(stateBefore == stateAfter)
    }
  }
}
