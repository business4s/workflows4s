package workflows4s.example

import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, ScalaTestWithActorTestKit}
import org.apache.pekko.persistence.jdbc.testkit.scaladsl.SchemaUtils
import org.scalatest.freespec.AnyFreeSpecLike
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime
import workflows4s.example.withdrawal.*
import workflows4s.runtime.instanceengine.{Effect, FutureEffect, LazyFuture}
import workflows4s.runtime.pekko.PekkoRuntimeAdapter

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global

class PekkoWithdrawalWorkflowTest
    extends ScalaTestWithActorTestKit(ActorTestKit("MyCluster"))
    with AnyFreeSpecLike
    with WithdrawalWorkflowTestSuite[LazyFuture] {

  override def beforeAll(): Unit = {
    super.beforeAll()
    val _ = Await.result(SchemaUtils.createIfNotExists()(using testKit.system), 10.seconds)
    ()
  }

  override given effect: Effect[LazyFuture] = FutureEffect.futureEffect

  "pekko" - {
    val adapter = new PekkoRuntimeAdapter[testContext.Context.Ctx]("pekko-withdrawal")(using testKit.system)
    withdrawalTests(adapter)
  }
}
