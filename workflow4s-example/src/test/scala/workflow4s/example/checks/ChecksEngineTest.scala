package workflow4s.example.checks

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.typesafe.scalalogging.StrictLogging
import org.camunda.bpm.model.bpmn.Bpmn
import org.scalatest.freespec.AnyFreeSpec
import workflow4s.bpmn.BPMNConverter
import workflow4s.example.checks
import workflow4s.example.testuitls.TestUtils.SimpleSignalResponseOps
import workflow4s.wio.model.{WIOModel, WIOModelInterpreterModule}
import workflow4s.wio.simple.{InMemoryJournal, SimpleActor}

import java.io.File
import scala.reflect.Selectable.reflectiveSelectable
import scala.util.Random

class ChecksEngineTest extends AnyFreeSpec {

  "re-run pending checks until complete" in new Fixture {
    val check: Check[Unit] { val runNum: Int } = new Check[Unit] {
      var runNum                                       = 0
      override val key: CheckKey                    = CheckKey("foo")
      override def run(data: Unit): IO[CheckResult] = runNum match {
        case 0 | 1 => IO { runNum += 1 }.as(CheckResult.Pending())
        case _     => IO(CheckResult.Approved())
      }
    }
    val wf    = createWorkflow(List(check))
    wf.run()
    assert(check.runNum == 2)
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
    val journal = new InMemoryJournal

    def createWorkflow(checks: List[Check[Unit]]) = new ChecksActor(journal, ChecksInput((), checks))
  }
  class ChecksActor(journal: InMemoryJournal, input: ChecksInput) {
    val delegate        = SimpleActor.create(ChecksEngine.runChecks, input, journal)
    def run(): Unit     = delegate.proceed(runIO = true)
    def recover(): Unit = delegate.recover()

    def state: ChecksState = {
      val Right(st) = delegate.state
      st.asInstanceOf[ChecksState]
    }

    def review(decision: ReviewDecision) = delegate.handleSignal(ChecksEngine.reviewSignalDef)(decision).extract
  }

  case class StaticCheck[T <: CheckResult](result: T) extends Check[Unit] {
    override val key: CheckKey                    = CheckKey(Random.alphanumeric.take(10).mkString)
    override def run(data: Unit): IO[CheckResult] = IO(result)
  }

  def getModel(wio: ChecksEngine.Context.WIO[?, ?, ?, ?]): WIOModel = {
    val m = new WIOModelInterpreterModule {
      override val c: ChecksEngine.Context.type = ChecksEngine.Context
    }
    m.WIOModelInterpreter.run(wio)
  }

}
