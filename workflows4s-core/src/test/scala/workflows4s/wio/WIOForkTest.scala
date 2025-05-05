package workflows4s.wio

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{EitherValues, OptionValues}
import workflows4s.mermaid.MermaidRenderer
import workflows4s.testing.TestUtils

class WIOForkTest extends AnyFreeSpec with Matchers with OptionValues with EitherValues {

  import TestCtx3.*

  "WIO.fork" - {

    "should execute the branch which matches the input" in {
      val branch1 = WIO.branch[String].when(_ == "one")(WIO.pure("into one").done).done
      val branch2 = WIO.branch[String].when(_ == "two")(WIO.pure("into two").done).done

      val wf: WIO.Initial = createFork(branch1, branch2).provideInput("one")

      val (_, instance) = TestUtils.createInstance3(wf)
      val resultState   = instance.queryState()

      assert(resultState == "into one")
    }

    "should handle recursion at execution time" in {
      val loop = repeatUntil(
        WIO.pure.makeFrom[String].value(_ + ".").done,
      )({ value =>
        if (value.length >= 5) Right(value)
        else Left(value)
      })

      val wf: WIO.Initial = loop.provideInput("")

      val (_, instance) = TestUtils.createInstance3(wf)
      val resultState   = instance.queryState()

      assert(resultState == ".....")
    }

    "should handle recursion when generating a diagram" in {
      val loop = repeatUntil(
        WIO.pure.makeFrom[String].value(_ + ".").done,
      )({ value =>
        if (value.length >= 5) Right(value)
        else Left(value)
      })

      val wf: WIO.Initial = loop.provideInput("")

      val flowchart = MermaidRenderer.renderWorkflow(wf.toProgress, showTechnical = true)
      val rendered = flowchart.render

      assert(rendered ==
        """flowchart TD
          |node0@{ shape: circle, label: "Start"}
          |node1["@computation"]
          |node0 --> node1
          |""".stripMargin)
    }
  }

  def createFork[Err](
      branch1: WIO.Branch[String, Err, String],
      branch2: WIO.Branch[String, Err, String],
  ): WIO[String, Err, String] =
    WIO.fork
      .addBranch(branch1)
      .addBranch(branch2)
      .done

  def repeatUntil[BodyIn, Err, BodyOut <: State, LoopOut <: State](
      body: WIO[BodyIn, Err, BodyOut],
  )(repeat: BodyOut => Either[BodyIn, LoopOut]): WIO[BodyIn, Err, LoopOut] = {
    def loop: WIO[BodyIn, Err, LoopOut] = {
      val condRepeat = WIO
        .branch[BodyOut]
        .create(repeat.andThen(_.left.toOption))(loop)
        .done

      val condExit = WIO
        .branch[BodyOut]
        .create(repeat.andThen(_.toOption))(
          WIO.pure.makeFrom.value((x: LoopOut) => x).done,
        )
        .done

      val fork = WIO
        .fork[BodyOut]
        .addBranch(condRepeat)
        .addBranch(condExit)
        .done

      body.andThen(fork)
    }

    loop
  }

}
