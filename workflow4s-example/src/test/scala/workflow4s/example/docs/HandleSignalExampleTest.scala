package workflow4s.example.docs

import org.scalatest.freespec.AnyFreeSpec
import workflow4s.example.TestUtils

class HandleSignalExampleTest  extends AnyFreeSpec {

  "render" in {
    TestUtils.renderDocsExample(HandleSignalExample.doThings, "handle-signal")
  }

}
