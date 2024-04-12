package workflow4s.example.docs

import org.scalatest.freespec.AnyFreeSpec
import workflow4s.example.TestUtils

class ExamplesTest  extends AnyFreeSpec {

  "render" in {
    TestUtils.renderDocsExample(RunIOExample.doThings, "run-io")
    TestUtils.renderDocsExample(RunIOExample.doThingsWithError, "run-io-error")
    TestUtils.renderDocsExample(HandleSignalExample.doThings, "handle-signal")
    TestUtils.renderDocsExample(SequencingExample.sequence1, "and-then")
    TestUtils.renderDocsExample(SequencingExample.Dynamic.sequence1, "flat-map")
  }

}
