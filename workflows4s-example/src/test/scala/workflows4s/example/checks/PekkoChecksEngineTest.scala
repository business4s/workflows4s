package workflows4s.example.checks

import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, ScalaTestWithActorTestKit}
import org.apache.pekko.persistence.jdbc.testkit.scaladsl.SchemaUtils
import org.scalatest.freespec.AnyFreeSpecLike
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime
import workflows4s.example.withdrawal.checks.*
import workflows4s.runtime.instanceengine.{Effect, FutureEffect, LazyFuture}
import workflows4s.runtime.pekko.PekkoRuntimeAdapter
import workflows4s.testing.WorkflowTestAdapter

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global

class PekkoChecksEngineTest extends ScalaTestWithActorTestKit(ActorTestKit("MyCluster")) with AnyFreeSpecLike with ChecksEngineTestSuite[LazyFuture] {

  override def beforeAll(): Unit = {
    super.beforeAll()
    val _ = Await.result(SchemaUtils.createIfNotExists()(using testKit.system), 10.seconds)
    ()
  }

  override given effect: Effect[LazyFuture] = FutureEffect.futureEffect

  override def createTrackingCheck(pendingCount: Int): Check[LazyFuture, Unit] & { def runNum: Int } =
    new Check[LazyFuture, Unit] {
      var runNum = 0

      override def key: CheckKey = CheckKey("foo")

      override def run(data: Unit): LazyFuture[CheckResult] = {
        if runNum < pendingCount then {
          runNum += 1
          LazyFuture.successful(CheckResult.Pending())
        } else {
          LazyFuture.successful(CheckResult.Approved())
        }
      }
    }

  "pekko with LazyFuture" - {
    "should create a PekkoRuntimeAdapter for LazyFuture checks engine context" in {
      val adapter = new PekkoRuntimeAdapter[testContext.Context.Ctx]("pekko-checks-future")(using testKit.system)
      assert(adapter != null)
    }
  }

  "in-memory with LazyFuture" - {
    val adapter = new WorkflowTestAdapter.InMemory[LazyFuture, testContext.Context.Ctx]()
    checkEngineTests(adapter)
  }
}
