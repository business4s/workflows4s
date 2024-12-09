package workflows4s.example.checks

import scala.concurrent.Await
import scala.reflect.Selectable.reflectiveSelectable

import cats.Id
import cats.effect.IO
import com.typesafe.scalalogging.StrictLogging
import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, ScalaTestWithActorTestKit}
import org.apache.pekko.persistence.jdbc.testkit.scaladsl.SchemaUtils
import org.scalatest.Inside.inside
import org.scalatest.freespec.AnyFreeSpecLike
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}
import workflows4s.example.withdrawal.checks.*
import workflows4s.example.{TestClock, TestRuntimeAdapter, TestUtils}
import workflows4s.runtime.WorkflowInstance
import workflows4s.wio.WCState
import workflows4s.wio.model.{WIOModel, WIOModelInterpreter}

class PekkoChecksEngineTest extends ScalaTestWithActorTestKit(ActorTestKit("MyCluster")) with AnyFreeSpecLike with ChecksEngineTest.Suite {

  override def beforeAll(): Unit = {
    super.beforeAll()
    Await.result(SchemaUtils.createIfNotExists()(testKit.system), 10.seconds)
  }

  "pekko" - {
    checkEngineTests(new TestRuntimeAdapter.Pekko("checks-engine")(using testKit.system))
  }

}
