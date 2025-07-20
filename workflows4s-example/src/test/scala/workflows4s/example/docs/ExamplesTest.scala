package workflows4s.example.docs

import org.scalatest.freespec.AnyFreeSpec
import workflows4s.example.TestUtils
import workflows4s.example.docs.pullrequest.{PullRequestWorkflow, PullRequestWorkflowDraft}
import workflows4s.wio.WIO

case class ExampleConfig(name: String, workflow: WIO[?, ?, ?, ?], technical: Boolean = false)

class ExamplesTest extends AnyFreeSpec {

  private val examples = List(
    ExampleConfig("run-io", RunIOExample.doThings),
    ExampleConfig("run-io-error", RunIOExample.doThingsWithError),
    ExampleConfig("timer", TimerExample.waitForInput),
    ExampleConfig("handle-signal", HandleSignalExample.doThings),
    ExampleConfig("and-then", SequencingExample.sequence1),
    ExampleConfig("flat-map", SequencingExample.Dynamic.sequence1),
    ExampleConfig("handle-error-with", HandleErrorExample.errorHandled),
    ExampleConfig("simple-loop", LoopExample.Simple.loop),
    ExampleConfig("loop", LoopExample.loop),
    ExampleConfig("fork", ForkExample.fork),
    ExampleConfig("parallel", ParallelExample.parallel),
    ExampleConfig("interruption-signal", InterruptionExample.interruptedThroughSignal),
    ExampleConfig("checkpoint", CheckpointExample.checkpoint.checkpointed, technical = true),
    ExampleConfig("recovery", CheckpointExample.recovery.myWorkflow, technical = true),
    ExampleConfig("pure", PureExample.doThings),
    ExampleConfig("pure-error", PureExample.doThingsWithError),
    ExampleConfig("pull-request-draft", PullRequestWorkflowDraft.workflow),
    ExampleConfig("pull-request", PullRequestWorkflow.workflow),
    ExampleConfig("for-each-draft", ForEachExample.draft.forEachDraft),
    ExampleConfig("for-each", ForEachExample.real.forEachStep),
  )

  "examples" - {
    examples.foreach { config =>
      config.name in {
        TestUtils.renderDocsExample(config.workflow, config.name, technical = config.technical)
      }
    }

    "debug output" in {
      TestUtils.renderDebugToFile(PullRequestWorkflow.workflow.toProgress, "docs/pull-request.debug.txt")
    }
  }

  "render progress" in {
    val instance = PullRequestWorkflow.run
    TestUtils.renderDocsProgressExample(instance, "pull-request-completed")

  }

}
