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
                             |""".stripMargin)
      }
    }

    "should render recovery-only checkpoint" - {
      "without showing technical details when showTechnical=false" in {
        val wio = WIO.recover[TestState, SimpleEvent, TestState]((_, _) => ???)

        val flowchart = MermaidRenderer.renderWorkflow(wio.toProgress, showTechnical = false)
        val rendered  = flowchart.render

        assert(rendered == """flowchart TD
                             |node0@{ shape: circle, label: "Start"}
                             |""".stripMargin)
      }

      "with technical details when showTechnical=true" in {
        val wio = WIO.recover[TestState, SimpleEvent, TestState]((_, _) => ???)

        val flowchart = MermaidRenderer.renderWorkflow(wio.toProgress, showTechnical = true)
        val rendered  = flowchart.render

        assert(rendered == """flowchart TD
                             |node0@{ shape: circle, label: "Start"}
                             |node1@{ shape: hexagon, label: "fa:fa-wrench State Recovery"}
                             |node0 --> node1
                             |""".stripMargin)
      }
    }

    "should generate a valid URL for viewing the rendered Mermaid diagram" in {
      val (_, runIoStep1) = TestUtils.runIO
      val wio             = runIoStep1.checkpointed((_, _) => ???, (_, _) => ???)

      val flowchart = MermaidRenderer.renderWorkflow(wio.toProgress, showTechnical = false)

      val url = flowchart.toViewUrl

      // Verify the URL starts with the expected prefix
      assert(url.startsWith("https://mermaid.live/edit#base64:"))

      // Verify the URL contains the encoded diagram content
      val expectedContent =
        "eyJjb2RlIjoiZmxvd2NoYXJ0IFREXG5ub2RlMEB7IHNoYXBlOiBjaXJjbGUsIGxhYmVsOiBcIlN0YXJ0XCJ9XG5ub2RlMVtcIkBjb21wdXRhdGlvblwiXVxubm9kZTAgLS0-IG5vZGUxXG4ifQ=="
      assert(url == s"https://mermaid.live/edit#base64:${expectedContent}")
    }
  }
}
