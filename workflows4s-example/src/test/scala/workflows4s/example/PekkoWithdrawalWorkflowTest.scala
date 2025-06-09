package workflows4s.example

import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, ScalaTestWithActorTestKit}
import org.apache.pekko.persistence.jdbc.testkit.scaladsl.SchemaUtils
import org.scalamock.scalatest.MockFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.freespec.AnyFreeSpecLike
import workflows4s.runtime.pekko.PekkoRuntimeAdapter

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

//noinspection ForwardReference
class PekkoWithdrawalWorkflowTest
    extends ScalaTestWithActorTestKit(ActorTestKit("MyCluster"))
    with AnyFreeSpecLike
    with MockFactory
    with BeforeAndAfterAll
    with WithdrawalWorkflowTest.Suite {

  override def beforeAll(): Unit = {
    super.beforeAll()
    val _ = Await.result(SchemaUtils.createIfNotExists()(using testKit.system), 10.seconds)
    ()
  }

  "pekko" - {
    withdrawalTests(new PekkoRuntimeAdapter("withdrawal")(using testKit.system))
  }

}
