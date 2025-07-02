package workflow4s.example.pekko

import org.apache.pekko.remote.testkit.MultiNodeConfig
import workflows4s.example.WithdrawalWorkflowTest
import org.apache.pekko
import pekko.actor.typed.ActorSystem
import pekko.remote.testkit.MultiNodeSpec
import workflows4s.runtime.pekko.PekkoRuntimeAdapter
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.scalatest.BeforeAndAfterAll
import com.typesafe.config.ConfigFactory
import scala.concurrent.duration.DurationInt
import org.apache.pekko.cluster.Cluster
import org.apache.pekko.cluster.ClusterEvent.CurrentClusterState
import org.apache.pekko.cluster.ClusterEvent.MemberUp
import org.scalatest.matchers.should.Matchers
import scala.concurrent.Await
import org.apache.pekko.persistence.jdbc.testkit.scaladsl.SchemaUtils

object Config extends MultiNodeConfig {
  val node1 = role("node1")
  val node2 = role("node2")

  commonConfig(
    ConfigFactory
      .parseString("""
           pekko{
              actor.provider = cluster
              cluster.seed-nodes = [] #join cluster manually

              remote.artery.canonical {
                hostname = "127.0.0.1"
                port = 0 # random port
              }
           }
         """)
      .withFallback(ConfigFactory.load()),
  )
}

class PekkoClusterWithdrawalMultiJvmNode1 extends AbstractSpec
class PekkoClusterWithdrawalMultiJvmNode2 extends AbstractSpec

abstract class AbstractSpec extends MultiNodeSpec(Config) with WithdrawalWorkflowTest.Suite with BeforeAndAfterAll with Matchers{

  given ActorSystem[Nothing] = system.toTyped

  override def beforeAll() = {
    multiNodeSpecBeforeAll()
  }

  override def afterAll() = multiNodeSpecAfterAll()

  def initialParticipants = 2

  "Pekko Cluster Runtime" - {

    "cluster has 2 nodes" in within(15.seconds) {
      Cluster(system).subscribe(testActor, classOf[MemberUp])
      expectMsgClass(classOf[CurrentClusterState])

      val firstAddress = node(Config.node1).address
      val secondAddress = node(Config.node2).address

      Cluster(system).join(firstAddress) // all nodes join firstAddress

      receiveN(2).collect { case MemberUp(m) => m.address }.toSet shouldBe Set(firstAddress, secondAddress)

      Cluster(system).unsubscribe(testActor)

      testConductor.enter("all-up")
    }

    "withdrawal tests" - {
      runOn(Config.node1) {
        val _ = Await.result(SchemaUtils.createIfNotExists(), 10.seconds)
        withdrawalTests(new PekkoRuntimeAdapter("withdrawal"))
      }
    }
  }

}
