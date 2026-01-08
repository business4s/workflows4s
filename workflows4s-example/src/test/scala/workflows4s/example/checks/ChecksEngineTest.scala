package workflows4s.example.checks

import cats.effect.IO
import org.scalatest.freespec.AnyFreeSpec
import workflows4s.cats.CatsEffect
import workflows4s.example.TestUtils
import workflows4s.example.withdrawal.checks.*
import workflows4s.runtime.instanceengine.Effect
import workflows4s.testing.WorkflowTestAdapter

class ChecksEngineTest extends AnyFreeSpec with ChecksEngineTestSuite[IO] {

  override given effect: Effect[IO] = CatsEffect.ioEffect

  override def createTrackingCheck(pendingCount: Int): Check[IO, Unit] & { def runNum: Int } =
    new Check[IO, Unit] {
      var runNum = 0

      override def key: CheckKey = CheckKey("foo")

      override def run(data: Unit): IO[CheckResult] = runNum match {
        case n if n < pendingCount =>
          IO {
            runNum += 1
          }.as(CheckResult.Pending())
        case _                     => IO(CheckResult.Approved())
      }
    }

  "in-memory" - {
    val adapter = new WorkflowTestAdapter.InMemory[IO, testContext.Context.Ctx]()
    checkEngineTests(adapter)
  }

  "render models" in {
    val checksEngine = testContext.createEngine()
    val wf           = checksEngine.runChecks
    TestUtils.renderBpmnToFile(wf, "checks-engine.bpmn")
    TestUtils.renderMermaidToFile(wf.toProgress, "checks-engine.mermaid")
  }
}
