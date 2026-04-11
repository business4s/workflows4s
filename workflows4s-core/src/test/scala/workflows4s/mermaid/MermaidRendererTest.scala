package workflows4s.mermaid

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import workflows4s.testing.TestUtils
import workflows4s.wio.{TestCtx2, TestState}
import cats.effect.IO
import workflows4s.wio.WIO.Draft

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
                             |node2@{ shape: circle, label: "End"}
                             |node1 --> node2
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
                             |node5@{ shape: circle, label: "End"}
                             |node2 --> node5
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
                             |node1@{ shape: circle, label: "End"}
                             |node0 --> node1
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
                             |node2@{ shape: circle, label: "End"}
                             |node1 --> node2
                             |""".stripMargin)
      }
    }

    "should render error handler with generic edge when base has no declared error" in {
      val step: Draft[Ctx]       = WIO.draft.step("My step")
      val errHandler: Draft[Ctx] = WIO.draft.step("Handle error")
      val wio                    = step.handleErrorWith(errHandler)

      val flowchart = MermaidRenderer.renderWorkflow(wio.toProgress)
      val rendered  = flowchart.render

      assert(rendered == """flowchart TD
                           |node0@{ shape: circle, label: "Start"}
                           |node1["My step"]
                           |node0 --> node1
                           |node2["Handle error"]
                           |node1 -.->|"fa:fa-bolt error"| node2
                           |node3@{ shape: circle, label: "End"}
                           |node1 --> node3
                           |node2 --> node3
                           |""".stripMargin)
    }

    "should render error handler with specific error edge when base has declared error" in {
      val step: Draft[Ctx]       = WIO.draft.step("My step", error = "My error")
      val errHandler: Draft[Ctx] = WIO.draft.step("Handle error")
      val wio                    = step.handleErrorWith(errHandler)

      val flowchart = MermaidRenderer.renderWorkflow(wio.toProgress)
      val rendered  = flowchart.render

      assert(rendered == """flowchart TD
                           |node0@{ shape: circle, label: "Start"}
                           |node1["My step"]
                           |node0 --> node1
                           |node2["Handle error"]
                           |node1 -.->|"fa:fa-bolt My error"| node2
                           |node3@{ shape: circle, label: "End"}
                           |node1 --> node3
                           |node2 --> node3
                           |""".stripMargin)
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
        "eyJjb2RlIjoiZmxvd2NoYXJ0IFREXG5ub2RlMEB7IHNoYXBlOiBjaXJjbGUsIGxhYmVsOiBcIlN0YXJ0XCJ9XG5ub2RlMVtcIkBjb21wdXRhdGlvblwiXVxubm9kZTAgLS0-IG5vZGUxXG5ub2RlMkB7IHNoYXBlOiBjaXJjbGUsIGxhYmVsOiBcIkVuZFwifVxubm9kZTEgLS0-IG5vZGUyXG4ifQ=="
      assert(url == s"https://mermaid.live/edit#base64:${expectedContent}")
    }
  }
}
