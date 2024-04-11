package workflow4s.example.docs

import org.scalatest.freespec.AnyFreeSpec
import workflow4s.example.TestUtils

class RunIOExampleTest extends AnyFreeSpec {

  "render" in {
    TestUtils.renderDocsExample(RunIOExample.doThings, "run-io")
    TestUtils.renderDocsExample(RunIOExample.doThingsWithError, "run-io-error")
  }
  
}
