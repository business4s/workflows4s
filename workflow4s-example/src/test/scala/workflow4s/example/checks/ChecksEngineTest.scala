package workflow4s.example.checks

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.typesafe.scalalogging.StrictLogging
import org.camunda.bpm.model.bpmn.Bpmn
import org.scalatest.freespec.AnyFreeSpec
import workflow4s.bpmn.BPMNConverter
import workflow4s.wio.model.WIOModelInterpreter
import workflow4s.wio.simple.{InMemoryJournal, SimpleActor}

import java.io.File

class ChecksEngineTest extends AnyFreeSpec {

  "re-run pending checks until complete" in new Fixture {
    val check = new Check[Unit] {
      var run                                       = 0
      override val key: CheckKey                    = CheckKey("foo")
      override def run(data: Unit): IO[CheckResult] = run match {
        case 0 | 1 => IO { run += 1 }.as(CheckResult.Pending())
        case _     => IO(CheckResult.Approved())
      }
    }
    val wf    = createWorkflow(List(check))
    wf.run()
    assert(check.run == 2)
  }

  "reject if any rejects" in {
    fail()
  }

  "require review if needed and no rejections" in {
    fail()
  }

  "approve if all checks approve" in {
    fail()
  }

  "render bpmn model" in {
    val wf        = ChecksEngine.runChecks(ChecksInput((), List()))
    val model     = WIOModelInterpreter.run(wf)
    val bpmnModel = BPMNConverter.convert(model, "checks-engine")
    Bpmn.writeModelToFile(new File("src/test/resources/checks-engine.bpmn"), bpmnModel)
  }

  trait Fixture extends StrictLogging {
    val journal = new InMemoryJournal

    def createWorkflow(checks: List[Check[Unit]]) = new ChecksActor(journal, ChecksInput((), checks))
  }
  class ChecksActor(journal: InMemoryJournal, input: ChecksInput) {
    val delegate        = SimpleActor.create(ChecksEngine.runChecks(input), ChecksState.empty, journal)
    def run(): Unit     = delegate.proceed(runIO = true)
    def recover(): Unit = delegate.recover()

  }

}
