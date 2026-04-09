package workflows4s.example.checks

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, ScalaTestWithActorTestKit}
import org.apache.pekko.persistence.jdbc.testkit.scaladsl.SchemaUtils
import org.scalatest.freespec.AnyFreeSpecLike
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime
import workflows4s.example.withdrawal.checks.ChecksEngine
import workflows4s.runtime.pekko.PekkoRuntimeAdapter
import workflows4s.wio.LiftWorkflowEffect

import scala.concurrent.{Await, Future}

class PekkoChecksEngineTest extends ScalaTestWithActorTestKit(ActorTestKit("MyCluster")) with AnyFreeSpecLike with ChecksEngineTest.Suite {

  private given LiftWorkflowEffect[ChecksEngine.Context.Ctx, Future] =
    LiftWorkflowEffect.andThen(summon[LiftWorkflowEffect[ChecksEngine.Context.Ctx, IO]], [A] => (fa: IO[A]) => fa.unsafeToFuture())

  override def beforeAll(): Unit = {
    super.beforeAll()
    val _ = Await.result(SchemaUtils.createIfNotExists()(using testKit.system), 10.seconds)
    ()
  }

  "pekko" - {
    checkEngineTests(new PekkoRuntimeAdapter[ChecksEngine.Context.Ctx]("checks-engine"))
  }

}
