package workflows4s.example

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, ScalaTestWithActorTestKit}
import org.apache.pekko.persistence.jdbc.testkit.scaladsl.SchemaUtils
import org.scalamock.scalatest.MockFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.freespec.AnyFreeSpecLike

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
    ()
  }

  "pekko" - {
    withdrawalTests(new TestRuntimeAdapter.Pekko("withdrawal")(using testKit.system))
  }

}
