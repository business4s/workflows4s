package workflows4s.mermaid

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import workflows4s.testing.{SyncWorkflowInstance, TestUtils}
import workflows4s.wio.{TestCtx2, TestState}

import scala.concurrent.duration.FiniteDuration
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

    "should treat bare Pure error handler as a passthrough routing the error to End" in {
      val step: Draft[Ctx] = WIO.draft.step("My step", error = "My error")
      val handler          = WIO.pure(TestState.empty).done
      val wio              = step.handleErrorWith(handler)

      val flowchart = MermaidRenderer.renderWorkflow(wio.toProgress)
      val rendered  = flowchart.render

      assert(rendered == """flowchart TD
                           |node0@{ shape: circle, label: "Start"}
                           |node1["My step"]
                           |node0 --> node1
                           |node2@{ shape: circle, label: "End"}
                           |node1 --> node2
                           |node1 -.->|"fa:fa-bolt My error"| node2
                           |""".stripMargin)
    }

    "should route bare Pure error handler's error edge to the next step" in {
      val step: Draft[Ctx] = WIO.draft.step("My step", error = "My error")
      val next: Draft[Ctx] = WIO.draft.step("Next step")
      val handler          = WIO.pure(TestState.empty).done
      val wio              = step.handleErrorWith(handler) >>> next

      val flowchart = MermaidRenderer.renderWorkflow(wio.toProgress)
      val rendered  = flowchart.render

      assert(rendered == """flowchart TD
                           |node0@{ shape: circle, label: "Start"}
                           |node1["My step"]
                           |node0 --> node1
                           |node2["Next step"]
                           |node1 --> node2
                           |node1 -.->|"fa:fa-bolt My error"| node2
                           |node3@{ shape: circle, label: "End"}
                           |node2 --> node3
                           |""".stripMargin)
    }

    "should render description for runIO step" in {
      val step: Draft[Ctx] = WIO.draft.step("My step", description = "Some details")

      val flowchart = MermaidRenderer.renderWorkflow(step.toProgress)
      val rendered  = flowchart.render

      assert(rendered == """flowchart TD
                           |node0@{ shape: circle, label: "Start"}
                           |node1["My step"]
                           |node0 --> node1
                           |node2@{ shape: braces, label: "Some details"}
                           |node1 -.- node2
                           |node3@{ shape: circle, label: "End"}
                           |node1 --> node3
                           |""".stripMargin)
    }

    "should render description for pure step" in {
      val step = WIO.pure(TestState.empty).named("My pure", description = "Pure details")

      val flowchart = MermaidRenderer.renderWorkflow(step.toProgress)
      val rendered  = flowchart.render

      assert(rendered == """flowchart TD
                           |node0@{ shape: circle, label: "Start"}
                           |node1["My pure"]
                           |node0 --> node1
                           |node2@{ shape: braces, label: "Pure details"}
                           |node1 -.- node2
                           |node3@{ shape: circle, label: "End"}
                           |node1 --> node3
                           |""".stripMargin)
    }

    "should render description for signal step" in {
      val step: Draft[Ctx] = WIO.draft.signal("My signal", description = "Signal details")

      val flowchart = MermaidRenderer.renderWorkflow(step.toProgress)
      val rendered  = flowchart.render

      assert(rendered == """flowchart TD
                           |node0@{ shape: circle, label: "Start"}
                           |node1@{ shape: stadium, label: "fa:fa-envelope My signal"}
                           |node0 --> node1
                           |node2["Handle My signal"]
                           |node1 --> node2
                           |node3@{ shape: braces, label: "Signal details"}
                           |node2 -.- node3
                           |node4@{ shape: circle, label: "End"}
                           |node2 --> node4
                           |""".stripMargin)
    }

    "should render loops" - {
      import cats.effect.IO

      // Builds a polling loop: run "Poll" until it was executed `iterations` times, with a timer between the iterations.
      def pollingLoop(iterations: Int) = {
        val (pollId, poll)         = TestUtils.runIOCustom(IO.unit, name = Some("Poll"))
        val (timerDuration, timer) = TestUtils.timer(secs = 1)
        val loop                   = WIO
          .repeat(poll)
          .until(_.executed.count(_ == pollId) == iterations)
          .onRestart(timer)
          .named(conditionName = "Done?", releaseBranchName = "Yes", restartBranchName = "No")
        (loop, timerDuration)
      }

      // Creates an instance of `wio` and runs `polls` iterations of its polling loop.
      def runPolls(
          wio: WIO[TestState, Nothing, TestState],
          timerDuration: FiniteDuration,
          polls: Int,
      ): SyncWorkflowInstance[TestCtx2.Ctx] = {
        val (clock, instance) = TestUtils.createInstance2(wio)
        for i <- 1 to polls do {
          instance.wakeup()                                // polls once, then starts the timer or leaves the loop
          if i < polls then clock.advanceBy(timerDuration) // the next wakeup releases the timer
        }
        instance
      }

      "collapsed into a single node by default" in {
        val (loop, timerDuration) = pollingLoop(iterations = 3)
        val instance              = runPolls(loop, timerDuration, polls = 2)

        val rendered = MermaidRenderer.renderWorkflow(instance.getProgress).render

        assert(rendered == """flowchart TD
                             |node0:::executed@{ shape: circle, label: "Start"}
                             |node1@{ shape: procs, label: "fa:fa-repeat Done? ×2"}
                             |node0 --> node1
                             |node2@{ shape: circle, label: "End"}
                             |node1 --> node2
                             |classDef executed fill:#0e0
                             |""".stripMargin)
      }

      "unrolled when requested" in {
        val (loop, timerDuration) = pollingLoop(iterations = 3)
        val instance              = runPolls(loop, timerDuration, polls = 2)

        val rendered = MermaidRenderer.renderWorkflow(instance.getProgress, loopRendering = MermaidRenderer.LoopRendering.Unrolled).render

        assert(rendered == """flowchart TD
                             |node0:::executed@{ shape: circle, label: "Start"}
                             |node1["Poll"]:::executed
                             |node0 --> node1
                             |node2:::executed@{ shape: stadium, label: "fa:fa-clock  (dynamic)"}
                             |node1 --> node2
                             |node3["Poll"]:::executed
                             |node2 --> node3
                             |node4@{ shape: stadium, label: "fa:fa-clock  (dynamic)"}
                             |node3 --> node4
                             |node5@{ shape: circle, label: "End"}
                             |node4 --> node5
                             |classDef executed fill:#0e0
                             |""".stripMargin)
      }

      "collapsed after the loop has finished" in {
        val (loop, timerDuration) = pollingLoop(iterations = 3)
        val finish                = WIO.pure.makeFrom[TestState].value(identity).named("Finish")
        val instance              = runPolls(loop >>> finish, timerDuration, polls = 3)

        val rendered = MermaidRenderer.renderWorkflow(instance.getProgress).render

        assert(rendered == """flowchart TD
                             |node0:::executed@{ shape: circle, label: "Start"}
                             |node1:::executed@{ shape: procs, label: "fa:fa-repeat Done? ×3"}
                             |node0 --> node1
                             |node2["Finish"]:::executed
                             |node1 --> node2
                             |node3@{ shape: circle, label: "End"}
                             |node2 --> node3
                             |classDef executed fill:#0e0
                             |""".stripMargin)
      }

      "collapsed with the errors that can leave the loop" in {
        val (_, failingPoll) = TestUtils.errorIO
        val (_, timer)       = TestUtils.timer(secs = 1)
        val loop             = WIO
          .repeat[TestState, String, Nothing](failingPoll)
          .until(_ => false)
          .onRestart(timer)
          .named(conditionName = "Done?")
        val handler          = WIO.pure.makeFrom[(TestState, String)].value((st, err) => st.addError(err)).named("Handle")
        val (_, instance)    = TestUtils.createInstance2(loop.handleErrorWith(handler))

        instance.wakeup() // poll 1 fails, the loop ends with the error

        val rendered = MermaidRenderer.renderWorkflow(instance.getProgress).render

        assert(rendered == """flowchart TD
                             |node0:::executed@{ shape: circle, label: "Start"}
                             |node1@{ shape: procs, label: "fa:fa-repeat Done? ×1"}
                             |node0 --> node1
                             |node2["Handle"]:::executed
                             |node1 -.->|"fa:fa-bolt String"| node2
                             |node3@{ shape: circle, label: "End"}
                             |node1 --> node3
                             |node2 --> node3
                             |classDef executed fill:#0e0
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
        "eyJjb2RlIjoiZmxvd2NoYXJ0IFREXG5ub2RlMEB7IHNoYXBlOiBjaXJjbGUsIGxhYmVsOiBcIlN0YXJ0XCJ9XG5ub2RlMVtcIkBjb21wdXRhdGlvblwiXVxubm9kZTAgLS0-IG5vZGUxXG5ub2RlMkB7IHNoYXBlOiBjaXJjbGUsIGxhYmVsOiBcIkVuZFwifVxubm9kZTEgLS0-IG5vZGUyXG4ifQ=="
      assert(url == s"https://mermaid.live/edit#base64:${expectedContent}")
    }
  }
}
