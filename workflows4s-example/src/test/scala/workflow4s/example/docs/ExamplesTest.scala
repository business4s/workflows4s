package workflow4s.example.docs

import org.scalatest.freespec.AnyFreeSpec
import workflow4s.example.TestUtils
import workflow4s.example.docs.pullrequest.PullRequestWorkflow

class ExamplesTest  extends AnyFreeSpec {

  "render" in {
    TestUtils.renderDocsExample(RunIOExample.doThings, "run-io")
    TestUtils.renderDocsExample(RunIOExample.doThingsWithError, "run-io-error")
    TestUtils.renderDocsExample(HandleSignalExample.doThings, "handle-signal")
    TestUtils.renderDocsExample(SequencingExample.sequence1, "and-then")
    TestUtils.renderDocsExample(SequencingExample.Dynamic.sequence1, "flat-map")
    TestUtils.renderDocsExample(HandleErrorExample.errorHandled, "handle-error-with")
    TestUtils.renderDocsExample(LoopExample.Simple.loop, "simple-loop")
    TestUtils.renderDocsExample(LoopExample.loop, "loop")
    TestUtils.renderDocsExample(PullRequestWorkflow.workflow, "pull-request" +
      "")
  }

}
