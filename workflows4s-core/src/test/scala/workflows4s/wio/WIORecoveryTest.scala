package workflows4s.wio

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import workflows4s.testing.TestUtils

class WIORecoveryTest extends AnyFreeSpec with Matchers {

  import TestCtx2.*

  "WIO.Recovery" - {

    "recover state from an event" in {
      val stepId = StepId.random
      case class MyEvent(state: TestState) extends TestCtx2.Event
      val event = MyEvent(TestState(List(stepId), List()))

      val wio     = WIO.recover((_, evt: MyEvent) => evt.state)
      val (_, wf) = TestUtils.createInstance2(wio)

      wf.recover(Seq(event))
      assert(wf.queryState() == event.state)
    }

    "recover state from multiple events" in {
      val List(stepId1, stepId2, stepId3) = List.fill(3)(StepId.random)

      case class MyEvent1(state: TestState) extends TestCtx2.Event
      case class MyEvent2(state: TestState) extends TestCtx2.Event

      val recover  = WIO.recover((state: TestState, evt: MyEvent1) => state ++ evt.state)
      val recover2 = WIO.recover((state: TestState, evt: MyEvent2) => state ++ evt.state)

      val wio     = recover >>> recover >>> recover2
      val (_, wf) = TestUtils.createInstance2(wio)
      wf.recover(
        Seq(
          MyEvent1(TestState(List(stepId1), List())),
          MyEvent1(TestState(List(stepId2), List())),
          MyEvent2(TestState(List(stepId3), List())),
        ),
      )
      assert(wf.queryState().executed == List(stepId1, stepId2, stepId3))
    }

  }
}
