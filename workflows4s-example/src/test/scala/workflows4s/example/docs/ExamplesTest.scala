package workflows4s.example.docs

import org.scalatest.freespec.AnyFreeSpec
import workflows4s.example.TestUtils
import workflows4s.example.docs.pullrequest.{PullRequestWorkflow, PullRequestWorkflowDraft}

class ExamplesTest extends AnyFreeSpec {

  "render" in {
    TestUtils.renderDocsExample(RunIOExample.doThings, "run-io")
    TestUtils.renderDocsExample(RunIOExample.doThingsWithError, "run-io-error")
    TestUtils.renderDocsExample(TimerExample.waitForInput, "timer")
    TestUtils.renderDocsExample(HandleSignalExample.doThings, "handle-signal")
    TestUtils.renderDocsExample(SequencingExample.sequence1, "and-then")
    TestUtils.renderDocsExample(SequencingExample.Dynamic.sequence1, "flat-map")
    TestUtils.renderDocsExample(HandleErrorExample.errorHandled, "handle-error-with")
    TestUtils.renderDocsExample(LoopExample.Simple.loop, "simple-loop")
    TestUtils.renderDocsExample(LoopExample.loop, "loop")
    TestUtils.renderDocsExample(ForkExample.fork, "fork")
    TestUtils.renderDocsExample(ParallelExample.parallel, "parallel")
    TestUtils.renderDocsExample(InterruptionExample.interruptedThroughSignal, "interruption-signal")
    TestUtils.renderDocsExample(PullRequestWorkflowDraft.workflow, "pull-request-draft")
    TestUtils.renderDocsExample(PullRequestWorkflow.workflow, "pull-request")
  }

}
