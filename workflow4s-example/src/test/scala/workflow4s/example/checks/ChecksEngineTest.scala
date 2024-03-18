package workflow4s.example.checks

import org.camunda.bpm.model.bpmn.Bpmn
import org.scalatest.freespec.AnyFreeSpec
import workflow4s.bpmn.BPMNConverter
import workflow4s.wio.model.WIOModelInterpreter

import java.io.File

class ChecksEngineTest extends AnyFreeSpec {

  "re-run pending checks until complete" in {
    fail()
  }

  "reject if any rejects" in {
    fail()
  }

  "require signal if needed and no rejections" in {
    fail()
  }

  "require review if needed and no signals needed" in {
    fail()
  }

  "approve if no checks trigger" in {
    fail()
  }

  "render bpmn model" in {
    val wf        = ChecksEngine.runChecks(ChecksInput((), List()))
    val model     = WIOModelInterpreter.run(wf)
    val bpmnModel = BPMNConverter.convert(model, "checks-engine")
    Bpmn.writeModelToFile(new File("src/test/resources/checks-engine.bpmn"), bpmnModel)
  }

}
