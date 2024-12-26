package workflows4s.wio

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class WIOTransformTest extends AnyFreeSpec with Matchers {

  object TestCtx extends WorkflowContext {
    type Event = String
    type State = String
  }
  import TestCtx.*

  extension [In, Out <: WCState[Ctx]](wio: WIO[In, Nothing, Out]) {
    def toWorkflow[In1 <: In & WCState[Ctx]](state: In1): ActiveWorkflow[Ctx] = ActiveWorkflow(wio, state, None)
  }

  "WIO.Transform" - {

    "map" in {
      val wf: ActiveWorkflow[TestCtx.Ctx] = WIO
        .pure("myValue")
        .done
        .map(_.toUpperCase)
        .toWorkflow("")

      val state = wf.liveState(Instant.now)
      assert(state == "MYVALUE")
    }

    "transformInput" in {
      val wf: ActiveWorkflow[TestCtx.Ctx] = WIO
        .pure
        .makeUsing[String]
        .value(identity)
        .done
        .transformInput[String](_.toUpperCase)
        .toWorkflow("initial state")

      val state = wf.liveState(Instant.now)
      assert(state == "INITIAL STATE")
    }

  }

  def ignore[A, B, C]: (A, B) => C = (_, _) => ???
}
