package workflows4s.example.checks

import cats.Id
import cats.effect.IO
import com.typesafe.scalalogging.StrictLogging
import org.scalatest.Inside.inside
import org.scalatest.freespec.{AnyFreeSpec, AnyFreeSpecLike}
import workflows4s.example.withdrawal.checks.*
import workflows4s.example.TestUtils
import workflows4s.runtime.WorkflowInstance
import workflows4s.testing.{TestClock, TestRuntimeAdapter}
import workflows4s.wio.WCState

import scala.annotation.nowarn
import scala.reflect.Selectable.reflectiveSelectable

class ChecksEngineTest extends AnyFreeSpec with ChecksEngineTest.Suite {

  "in-memory-sync" - {
    checkEngineTests(TestRuntimeAdapter.InMemorySync())
  }

  "in-memory" - {
    checkEngineTests(TestRuntimeAdapter.InMemory())
  }

  "render bpmn model" in {
    val wf = ChecksEngine.runChecks
    TestUtils.renderBpmnToFile(wf, "checks-engine.bpmn")
  }
  "render mermaid model" in {
    val wf = ChecksEngine.runChecks
    TestUtils.renderMermaidToFile(wf.toProgress, "checks-engine.mermaid")
  }

}
object ChecksEngineTest {

  trait Suite extends AnyFreeSpecLike {

    def checkEngineTests(getRuntime: => TestRuntimeAdapter[ChecksEngine.Context, ?]) = {

      "re-run pending checks until complete" in new Fixture {
        val check: Check[Unit] { def runNum: Int } = new Check[Unit] {
          @nowarn("msg=unused private member") // compiler went nuts
          var runNum = 0

          override def key: CheckKey = CheckKey("foo")

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
        val clock   = new TestClock
        val runtime = getRuntime

        def createWorkflow(checks: List[Check[Unit]]) = {
          val wf = runtime.runWorkflow(
            ChecksEngine.runChecks.provideInput(ChecksInput((), checks)),
            null: ChecksState,
            clock,
          )
          new ChecksActor(wf, checks)
        }

        def checkRecovery(firstActor: ChecksActor[runtime.Actor]) = {
          logger.debug("Checking recovery")
          val originalState = firstActor.state
          val secondActor   = runtime.recover(firstActor.wf)
          assert(secondActor.queryState() == originalState)
        }

      }

    }
  }

  class ChecksActor[Actor <: WorkflowInstance[Id, WCState[ChecksEngine.Context]]](val wf: Actor, val checks: List[Check[Unit]]) {
    def run(): Unit                            = wf.wakeup()
    def state: ChecksState                     = wf.queryState()
    def review(decision: ReviewDecision): Unit = {
      import workflows4s.example.testuitls.TestUtils.*
      wf.deliverSignal(ChecksEngine.Signals.review, decision).extract
    }
  }

}
