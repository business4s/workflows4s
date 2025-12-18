package workflows4s.runtime.pekko

import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, ScalaTestWithActorTestKit}
import org.apache.pekko.persistence.jdbc.testkit.scaladsl.SchemaUtils
import org.scalatest.freespec.AnyFreeSpecLike
import workflows4s.testing.IOWorkflowRuntimeTest
import workflows4s.wio.IOTestCtx2

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class PekkoRuntimeTest extends ScalaTestWithActorTestKit(ActorTestKit("MyCluster")) with AnyFreeSpecLike with IOWorkflowRuntimeTest.Suite {

  override def beforeAll(): Unit = {
    super.beforeAll()
    val _ = Await.result(SchemaUtils.createIfNotExists()(using testKit.system), 10.seconds)
    ()
  }

  "generic tests" - {
    ioWorkflowTests(new PekkoRuntimeAdapter[IOTestCtx2.Ctx]("generic-test-workflow"))
  }

}
