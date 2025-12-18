package workflows4s.example.checks

import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, ScalaTestWithActorTestKit}
import org.apache.pekko.persistence.jdbc.testkit.scaladsl.SchemaUtils
import org.scalatest.freespec.AnyFreeSpecLike
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime

import scala.concurrent.Await

// ChecksEngine workflow logic is tested via in-memory runtime (ChecksEngineTest).
// Generic Pekko functionality is tested via PekkoRuntimeTest.
class PekkoChecksEngineTest extends ScalaTestWithActorTestKit(ActorTestKit("MyCluster")) with AnyFreeSpecLike {

  override def beforeAll(): Unit = {
    super.beforeAll()
    val _ = Await.result(SchemaUtils.createIfNotExists()(using testKit.system), 10.seconds)
    ()
  }

  "pekko" - {
    "checks engine integration test disabled (effect type mismatch)" in {
      // See class comment for explanation
      pending
    }
  }

}
