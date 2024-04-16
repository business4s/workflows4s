package workflow4s.example.checks

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.typesafe.scalalogging.StrictLogging
import org.camunda.bpm.model.bpmn.Bpmn
import org.scalatest.Inside.inside
import org.scalatest.freespec.AnyFreeSpec
import workflow4s.bpmn.BPMNConverter
import workflow4s.example.TestClock
import workflow4s.example.testuitls.TestUtils.SimpleSignalResponseOps
import workflow4s.example.withdrawal.checks.*
import workflow4s.wio.KnockerUpper
import workflow4s.wio.model.{WIOModel, WIOModelInterpreter}
import workflow4s.wio.simple.{InMemoryJournal, SimpleActor}
import scala.reflect.Selectable.reflectiveSelectable

import java.io.File
import java.time.Clock
import scala.util.Random

class ChecksEngineTest extends AnyFreeSpec {

  "re-run pending checks until complete" in new Fixture {
    val check: Check[Unit] { val runNum: Int } = new Check[Unit] {
      var runNum                                    = 0
      override val key: CheckKey                    = CheckKey("foo")
      override def run(data: Unit): IO[CheckResult] = runNum match {
        case 0 | 1 => IO { runNum += 1 }.as(CheckResult.Pending())
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
  }

  "render bpmn model" in {
    val wf        = ChecksEngine.runChecks
    val model     = getModel(wf)
    val bpmnModel = BPMNConverter.convert(model, "checks-engine")
    Bpmn.writeModelToFile(new File("src/test/resources/checks-engine.bpmn"), bpmnModel)
  }

  trait Fixture extends StrictLogging {
    val journal      = new InMemoryJournal[ChecksEvent]
    val clock        = new TestClock
    val knockerUpper = KnockerUpper.noop

    def createWorkflow(checks: List[Check[Unit]]) = new ChecksActor(journal, ChecksInput((), checks), clock, knockerUpper)
  }
  class ChecksActor(journal: InMemoryJournal[ChecksEvent], input: ChecksInput, clock: Clock, knockerUpper: KnockerUpper) {
    val delegate        = SimpleActor
      .createWithState[ChecksEngine.Context.type, ChecksInput](ChecksEngine.runChecks, input, null: ChecksState, journal, clock, knockerUpper)
    def run(): Unit     = delegate.proceed(runIO = true)
    def recover(): Unit = delegate.recover()

    def state: ChecksState = delegate.state

    def review(decision: ReviewDecision) = delegate.handleSignal(ChecksEngine.Signals.review)(decision).extract
  }

  def getModel(wio: ChecksEngine.Context.WIO[?, ?, ?]): WIOModel = {
    WIOModelInterpreter.run(wio)
  }

}
