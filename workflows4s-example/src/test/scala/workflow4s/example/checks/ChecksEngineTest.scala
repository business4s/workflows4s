package workflow4s.example.checks

import cats.effect.IO
import com.typesafe.scalalogging.StrictLogging
import org.scalatest.Inside.inside
import org.scalatest.freespec.AnyFreeSpec
import workflow4s.example.TestRuntimeAdapter.TestRuntime
import workflow4s.example.testuitls.TestUtils.SignalResponseOps
import workflow4s.example.withdrawal.checks.*
import workflow4s.example.{TestClock, TestRuntimeAdapter, TestUtils}
import workflow4s.wio.model.{WIOModel, WIOModelInterpreter}
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.persistence.jdbc.testkit.scaladsl.SchemaUtils
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}

import scala.concurrent.Await
import scala.reflect.Selectable.reflectiveSelectable

class ChecksEngineTest extends AnyFreeSpec with BeforeAndAfterAll with BeforeAndAfter {

  val testKit = ActorTestKit("MyCluster")
  override def afterAll(): Unit = testKit.shutdownTestKit()

  before {
    val f = SchemaUtils.createIfNotExists()(testKit.system)
    Await.result(f, 3.seconds)
  }


//  "in-memory-sync" - {
//    checkEngineTests(TestRuntimeAdapter.InMemorySync)
//  }
//  "in-memory" - {
//    checkEngineTests(TestRuntimeAdapter.InMemory)
//  }
  "pekko" - {
    checkEngineTests(new TestRuntimeAdapter.Pekko("checks-engine")(testKit.system))
  }

  "render bpmn model" in {
    val wf = ChecksEngine.runChecks
    TestUtils.renderBpmnToFile(wf, "checks-engine.bpmn")
  }



  def checkEngineTests(runtime: TestRuntimeAdapter) = {

    "re-run pending checks until complete" in new Fixture {
      val check: Check[Unit] { val runNum: Int } = new Check[Unit] {
        var runNum                 = 0
        override val key: CheckKey = CheckKey("foo")

        override def run(data: Unit): IO[CheckResult] = runNum match {
          case 0 | 1 =>
            IO {
              runNum += 1
            }.as(CheckResult.Pending())
          case _     => IO(CheckResult.Approved())
        }
      }
      val wf                                     = createWorkflow(List(check))
      wf.run()
      assert(check.runNum == 1)
      inside(wf.state) { case x: ChecksState.Pending =>
        assert(x.results == Map(check.key -> CheckResult.Pending()))
      }
      clock.advanceBy(ChecksEngine.retryBackoff)
      wf.run()
      assert(check.runNum == 2)
      inside(wf.state) { case x: ChecksState.Pending =>
        assert(x.results == Map(check.key -> CheckResult.Pending()))
      }
      clock.advanceBy(ChecksEngine.retryBackoff)
      wf.run()
      assert(wf.state == ChecksState.Decided(Map(check.key -> CheckResult.Approved()), Decision.ApprovedBySystem()))

      checkRecovery(wf)
    }

    "timeout checks" in new Fixture {
      val check: Check[Unit] = StaticCheck(CheckResult.Pending())
      val wf                 = createWorkflow(List(check))
      wf.run()
      inside(wf.state) { case x: ChecksState.Pending =>
        assert(x.results == Map(check.key -> CheckResult.Pending()))
      }
      clock.advanceBy(ChecksEngine.timeoutThreshold)
      wf.run()
      assert(wf.state == ChecksState.Executed(Map(check.key -> CheckResult.TimedOut())))
      wf.review(ReviewDecision.Approve)
      assert(
        wf.state == ChecksState.Decided(
          Map(check.key -> CheckResult.TimedOut()),
          Decision.ApprovedByOperator(),
        ),
      )

      checkRecovery(wf)
    }

    "reject if any rejects" in new Fixture {
      val check1 = StaticCheck(CheckResult.Approved())
      val check2 = StaticCheck(CheckResult.Rejected())
      val check3 = StaticCheck(CheckResult.RequiresReview())
      val wf     = createWorkflow(List(check1, check2, check3))
      wf.run()
      assert(
        wf.state == ChecksState.Decided(
          Map(
            check1.key -> check1.result,
            check2.key -> check2.result,
            check3.key -> check3.result,
          ),
          Decision.RejectedBySystem(),
        ),
      )

      checkRecovery(wf)
    }

    "approve through review" in new Fixture {
      val check1 = StaticCheck(CheckResult.Approved())
      val check2 = StaticCheck(CheckResult.RequiresReview())
      val wf     = createWorkflow(List(check1, check2))
      wf.run()
      wf.review(ReviewDecision.Approve)
      assert(
        wf.state == ChecksState.Decided(
          Map(
            check1.key -> check1.result,
            check2.key -> check2.result,
          ),
          Decision.ApprovedByOperator(),
        ),
      )

      checkRecovery(wf)
    }

    "approve if all checks approve" in new Fixture {
      val check1 = StaticCheck(CheckResult.Approved())
      val check2 = StaticCheck(CheckResult.Approved())
      val wf     = createWorkflow(List(check1, check2))
      wf.run()
      assert(
        wf.state == ChecksState.Decided(
          Map(
            check1.key -> check1.result,
            check2.key -> check2.result,
          ),
          Decision.ApprovedBySystem(),
        ),
      )

      checkRecovery(wf)
    }

    trait Fixture extends StrictLogging {
      val clock = new TestClock

      def createWorkflow(checks: List[Check[Unit]], events: Seq[ChecksEvent] = List()) = {
        val wf = runtime.runWorkflow[ChecksEngine.Context, ChecksInput](
          ChecksEngine.runChecks,
          ChecksInput((), checks),
          null: ChecksState,
          clock,
          events,
        )
        new ChecksActor(wf, checks)
      }

      def checkRecovery(firstActor: ChecksActor) = {
        logger.debug("Checking recovery")
        val secondActor = createWorkflow(firstActor.checks, firstActor.events)
        assert(secondActor.state == firstActor.state)
      }

    }
  }
  class ChecksActor(wf: TestRuntime[ChecksEngine.Context], val checks: List[Check[Unit]]) {
    def run(): Unit                      = wf.wakeup()
    def events: Seq[ChecksEvent]         = wf.getEvents
    def state: ChecksState               = wf.queryState()
    def review(decision: ReviewDecision) = wf.deliverSignal(ChecksEngine.Signals.review, decision).extract
  }

  def getModel(wio: ChecksEngine.Context.WIO[?, ?, ?]): WIOModel = {
    WIOModelInterpreter.run(wio)
  }

}
