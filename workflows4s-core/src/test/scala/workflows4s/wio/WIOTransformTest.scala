package workflows4s.wio

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class WIOTransformTest extends AnyFreeSpec with Matchers {

  import TestCtx.*

  "WIO.Transform" - {

    "map" in {
      val wf: ActiveWorkflow[Eff, Ctx] = WIO
        .pure("myValue")
        .done
        .map(_.toUpperCase)
        .toWorkflow("")

      val state = wf.liveState
      assert(state == "MYVALUE")
    }

    "transformInput" in {
      val wf: ActiveWorkflow[Eff, Ctx] = WIO.pure
        .makeFrom[String]
        .value(identity)
        .done
        .transformInput[String](_.toUpperCase)
        .toWorkflow("initial state")

      val state = wf.liveState
      assert(state == "INITIAL STATE")
    }

  }
}
