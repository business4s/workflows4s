package workflows4s.example.docs

import org.scalatest.freespec.AnyFreeSpec
import workflows4s.example.TestUtils
import workflows4s.example.docs.draft.{
  DraftCheckpointExample,
  DraftForkExample,
  DraftInterruptionExample,
  DraftLoopExample,
  DraftParallelExample,
  DraftRetryExample,
  DraftSignalExample,
  DraftTimerExample,
}
import workflows4s.example.docs.pullrequest.{PullRequestWorkflow, PullRequestWorkflowDraft}
import workflows4s.wio.WIO

case class ExampleConfig(name: String, workflow: WIO[?, ?, ?, ?, ?], technical: Boolean = false)

class ExamplesTest extends AnyFreeSpec {

  private val examples = List(
    ExampleConfig("run-io", RunIOExample.doThings),
    ExampleConfig("run-io-error", RunIOExample.doThingsWithError),
    ExampleConfig("run-io-description", RunIOExample.doThingsWithDescription),
    ExampleConfig("timer", TimerExample.waitForInput),
    ExampleConfig("draft-timer", DraftTimerExample.waitForReview),
    ExampleConfig("handle-signal", HandleSignalExample.doThings),
    ExampleConfig("draft-signal", DraftSignalExample.awaitApproval),
    ExampleConfig("and-then", SequencingExample.sequence1),
    ExampleConfig("flat-map", SequencingExample.Dynamic.sequence1),
    ExampleConfig("handle-error-with", HandleErrorExample.errorHandled),
    ExampleConfig("simple-loop", LoopExample.Simple.loop),
    ExampleConfig("loop", LoopExample.loop),
    ExampleConfig("draft-loop", DraftLoopExample.loop),
    ExampleConfig("fork", ForkExample.fork),
    ExampleConfig("draft-choice", DraftForkExample.approvalWorkflow),
    ExampleConfig("parallel", ParallelExample.parallel),
    ExampleConfig("draft-parallel", DraftParallelExample.parallelWorkflow),
    ExampleConfig("interruption-signal", InterruptionExample.interruptedThroughSignal),
    ExampleConfig("draft-interruption", DraftInterruptionExample.documentProcessingWorkflow),
    ExampleConfig("checkpoint", CheckpointExample.checkpoint.checkpointed, technical = true),
    ExampleConfig("recovery", CheckpointExample.recovery.myWorkflow, technical = true),
    ExampleConfig("draft-checkpoint", DraftCheckpointExample.checkpointed, technical = true),
    ExampleConfig("draft-recovery", DraftCheckpointExample.recovery, technical = true),
    ExampleConfig("pure", PureExample.doThings),
    ExampleConfig("pure-error", PureExample.doThingsWithError),
    ExampleConfig("pull-request-draft", PullRequestWorkflowDraft.workflow),
    ExampleConfig("pull-request", PullRequestWorkflow.workflow),
    ExampleConfig("for-each-draft", ForEachExample.draft.forEachDraft),
    ExampleConfig("for-each", ForEachExample.real.forEachStep),
    ExampleConfig("retry", RetryExample.withRetry),
    ExampleConfig("draft-retry", DraftRetryExample.withRetry),
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

  "render progress" ignore {
    val instance = PullRequestWorkflow.run
    TestUtils.renderDocsProgressExample(instance, "pull-request-completed")
  }

}
