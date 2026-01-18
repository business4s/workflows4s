package workflows4s.runtime.pekko

import com.typesafe.config.{Config, ConfigFactory}
import io.altoo.serialization.kryo.pekko.PekkoKryoSerializer
import org.apache.pekko.actor.ExtendedActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.pattern.StatusReply
import org.apache.pekko.serialization.{JavaSerializer, Serializer}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import workflows4s.runtime.WorkflowInstance.UnexpectedSignal
import workflows4s.runtime.pekko.WorkflowBehavior.Command
import workflows4s.runtime.pekko.WorkflowBehaviorTest.*
import workflows4s.cats.IOWorkflowContext
import workflows4s.wio.model.WIOExecutionProgress
import workflows4s.wio.SignalDef

import scala.jdk.CollectionConverters.MapHasAsJava

class WorkflowBehaviorTest extends AnyFreeSpec with Matchers with BeforeAndAfterAll {

  val DummySignalDef = SignalDef[String, Int]()

  private val config: Config = ConfigFactory
    .parseMap(
      Map(
        "akka.remote.artery.canonical.port"    -> "0",
        "pekko.actor.allow-java-serialization" -> "on",
      ).asJava,
    )
    .withFallback(ConfigFactory.load())

  private val testKit     = ActorTestKit(config)
  private val typedSystem = testKit.system
  private val classic     = typedSystem.classicSystem
  private val extSystem   = classic.asInstanceOf[ExtendedActorSystem]

  // direct instantiation of both serializers:
  private val javaSer = new JavaSerializer(extSystem)
  private val kryoSer = new PekkoKryoSerializer(extSystem)

  override protected def afterAll(): Unit =
    testKit.shutdownTestKit()

  /** round‑trip a message through the given serializer and assert equality */
  private def assertSerializable(serializer: Serializer, msg: AnyRef): Unit = {
    val bytes        = serializer.toBinary(msg)
    val deserialized = serializer.fromBinary(bytes)
    val _            = deserialized shouldEqual msg
  }

  // --------------------------------
  // 👇 gather one of each Command[_] with real ActorRefs via TestKit.spawn
  // --------------------------------

  private def allCommands(): Seq[AnyRef] = {
    val probeA = testKit.spawn(Behaviors.ignore[DummyState])
    val probeB = testKit.spawn(Behaviors.ignore[StatusReply[Either[UnexpectedSignal, Int]]])
    val probeC = testKit.spawn(Behaviors.ignore[StatusReply[Unit]])
    val probeD = testKit.spawn(Behaviors.ignore[WIOExecutionProgress[DummyState]])
    val probeE = testKit.spawn(Behaviors.ignore[List[SignalDef[?, ?]]])

    Seq(
      Command.QueryState[DummyCtx.type](probeA),
      Command.DeliverSignal(DummySignalDef, "foo‑req", probeB),
      Command.Wakeup[DummyCtx.type](probeC),
      Command.GetProgress[DummyCtx.type](probeD),
      Command.GetExpectedSignals[DummyCtx.type](probeE),
      Command.Reply(probeA, DummyState(), unlock = true),
      Command.Persist[DummyCtx.type](DummyEvent(), Command.Reply(probeA, DummyState(), unlock = false)),
      Command.NoOp[DummyCtx.type](),
      Command.FollowupWakeup[DummyCtx.type](probeC),
    ).map(_.asInstanceOf[AnyRef])
  }

  "Command messages should be Java‑serializable" in {
    allCommands().foreach { msg =>
      assertSerializable(javaSer, msg)
    }
  }

  "Command messages should be Kryo‑serializable" in {
    allCommands().foreach { msg =>
      assertSerializable(kryoSer, msg)
    }
  }
}

object WorkflowBehaviorTest {
  object DummyCtx extends IOWorkflowContext {
    type Event = DummyEvent
    type State = DummyState
  }
  case class DummyEvent()
  case class DummyState()

}
