package workflows4s.runtime.pekko

import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.apache.pekko.remote.testkit.MultiNodeConfig
import org.apache.pekko.remote.testkit.MultiNodeSpec
import org.scalatest.freespec.AnyFreeSpecLike
import org.apache.pekko.cluster.Cluster
import org.apache.pekko.cluster.ClusterEvent.CurrentClusterState
import org.apache.pekko.cluster.ClusterEvent.MemberUp
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import cats.effect.unsafe.implicits.global
import cats.effect.IO
import org.apache.pekko.actor.typed.ActorSystem
import com.typesafe.config.ConfigFactory
import workflows4s.runtime.wakeup.SleepingKnockerUpper
import workflows4s.wio.WorkflowContext
import workflows4s.runtime.pekko.PekkoRuntime
import scala.concurrent.Future
import workflows4s.wio.SignalDef
import workflows4s.runtime.WorkflowInstance.UnexpectedSignal
import scala.concurrent.duration.*
import cats.implicits.*

class PekkoClusterMultiJvmNode1 extends AbstractPekkoClusterSpec
class PekkoClusterMultiJvmNode2 extends AbstractPekkoClusterSpec

object NodesConfig extends MultiNodeConfig {
  val node1 = role("node1")
  val node2 = role("node2")
  commonConfig(ConfigFactory.load("application-cluster-test.conf"))
}

private class AbstractPekkoClusterSpec extends MultiNodeSpec(NodesConfig) with AnyFreeSpecLike with Matchers with BeforeAndAfterAll {

  override def beforeAll() = multiNodeSpecBeforeAll() 

  override def afterAll() = multiNodeSpecAfterAll()

  override def initialParticipants = roles.size

  object WContext extends WorkflowContext {
    override type Event = StartEvent
    override type State = String

    val startSignal: SignalDef[String, Unit] = SignalDef()
    case class StartEvent(id: String)
  }

  import WContext.*
  private val workflow: WIO.Initial = WIO
    .handleSignal(startSignal)
    .using[Any]
    .purely[StartEvent]((_, id) => StartEvent(id))
    .handleEvent((_, _) => "state")
    .voidResponse
    .done

  private def initKnockerUpperAndRun(workflowIds: Int*)(using system: ActorSystem[Nothing]): IO[Unit] = {
    def startWorkflow(id: String, runtime: PekkoRuntime[WContext.Ctx]): Future[Either[UnexpectedSignal, Unit]] = {
      val instance = runtime.createInstance_(id)
      instance.deliverSignal(startSignal, id)
    }
    SleepingKnockerUpper
      .create()
      .use { knockerUpper =>
        val runtime = PekkoRuntime.create[WContext.Ctx]("hello", workflow, "start", knockerUpper)
        for {
          _ <- knockerUpper.initialize(_ => IO.println(s"Woke up!"))
          _ <- IO(runtime.initializeShard())
          _ <- IO(testConductor.enter("ready to create worflow instance"))
          _ <- workflowIds.traverse(id => IO.fromFuture(IO(startWorkflow(id.toString, runtime))).flatMap(_ => IO(system.log.info(s"[$id] done"))))
          _ <- IO.sleep(10.seconds)
        } yield ()
      }
  }

  "Pekko Cluster Runtime" - {

    given ActorSystem[Nothing] = system.toTyped

    "Cluster has 2 nodes" in within(15.seconds) {
      Cluster(system).subscribe(testActor, classOf[MemberUp])
      expectMsgClass(classOf[CurrentClusterState])

      val node1Address = node(NodesConfig.node1).address
      val node2Address = node(NodesConfig.node2).address

      Cluster(system).join(node1Address)

      receiveN(2).collect { case MemberUp(m) => m.address }.toSet shouldBe Set(node1Address, node2Address)

      Cluster(system).unsubscribe(testActor)

      testConductor.enter("all-up")
    }

    "Create workflow instances and deliver signals" in {
      runOn(NodesConfig.node1) {
        initKnockerUpperAndRun(123, 456).unsafeRunSync()
     // initKnockerUpperAndRun(123).unsafeRunSync() //no error
      }
    }

    "Create Node2 Runtime" in {
      runOn(NodesConfig.node2) {
        initKnockerUpperAndRun().unsafeRunSync() // to be ready to receive workload from node1
      }
    }

  }

}
