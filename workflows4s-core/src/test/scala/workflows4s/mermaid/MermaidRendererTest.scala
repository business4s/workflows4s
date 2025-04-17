package workflows4s.mermaid

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import workflows4s.testing.TestUtils
import workflows4s.wio.{TestCtx2, TestState}

class MermaidRendererTest extends AnyFreeSpec with Matchers {

  import TestCtx2.*

  "MermaidRenderer" - {
    "should render checkpoint" - {
      "without showing technical details when showTechnical=false" in {
        val (_, runIoStep1) = TestUtils.runIO
        val wio             = runIoStep1.checkpointed((_, _) => ???, (_, _) => ???)

        val flowchart = MermaidRenderer.renderWorkflow(wio.toProgress, showTechnical = false)
        val rendered  = flowchart.render

        assert(rendered == """flowchart TD
                             |node0@{ shape: circle, label: "Start"}
                             |node1["@computation"]
                             |node0 --> node1
                             |classDef executed fill:#0e0
                             |classDef checkpoint fill:transparent,stroke-dasharray:5 5,stroke:black
                             |""".stripMargin)
      }

      "with technical details when showTechnical=true" in {
        val (_, runIoStep1) = TestUtils.runIO
        val wio             = runIoStep1.checkpointed((_, _) => ???, (_, _) => ???)

        val flowchart = MermaidRenderer.renderWorkflow(wio.toProgress, showTechnical = true)
        val rendered  = flowchart.render

        assert(rendered == """flowchart TD
                             |node0@{ shape: circle, label: "Start"}
                             |node1:::checkpoint
                             |subgraph node1 ["Checkpoint"]
                             |node2["@computation"]
                             |node0 --> node2
                             |end
                             |classDef executed fill:#0e0
                             |classDef checkpoint fill:transparent,stroke-dasharray:5 5,stroke:black
                             |""".stripMargin)
      }
    }
  }
}
