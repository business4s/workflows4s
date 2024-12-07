package workflow4s.example

import java.time.{Clock, Instant, ZoneId, ZoneOffset}

import scala.concurrent.Await
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.DurationConverters.ScalaDurationOps

import cats.effect.IO
import com.typesafe.scalalogging.StrictLogging
import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, ScalaTestWithActorTestKit}
import org.apache.pekko.persistence.jdbc.testkit.scaladsl.SchemaUtils
import org.scalamock.scalatest.MockFactory
import org.scalatest.Inside.inside
import org.scalatest.concurrent.Eventually.eventually
import org.scalatest.freespec.AnyFreeSpecLike
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}
import workflow4s.example.checks.StaticCheck
import workflow4s.example.withdrawal.*
import workflow4s.example.withdrawal.WithdrawalService.{ExecutionResponse, Fee, Iban}
import workflow4s.example.withdrawal.WithdrawalSignal.CreateWithdrawal
import workflow4s.example.withdrawal.checks.*
import workflow4s.wio.model.{WIOModel, WIOModelInterpreter}

//noinspection ForwardReference
class PekkoWithdrawalWorkflowTest
    extends ScalaTestWithActorTestKit(ActorTestKit("MyCluster"))
    with AnyFreeSpecLike
    with MockFactory
    with BeforeAndAfterAll
    with WithdrawalWorkflowTest.Suite {

  override def beforeAll(): Unit = {
    super.beforeAll()
    Await.result(SchemaUtils.createIfNotExists()(testKit.system), 10.seconds)
  }

  "pekko" - {
    withdrawalTests(new TestRuntimeAdapter.Pekko("withdrawal")(using testKit.system))
  }

}
