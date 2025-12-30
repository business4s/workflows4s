package workflows4s.wio

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import workflows4s.testing.TestUtils

class WIOCheckpointTest extends AnyFreeSpec with Matchers {

  import TestCtx2.*

  // Define checkpoint event at class level to avoid type erasure warnings
  case class MyCheckpoint(state: TestState) extends TestCtx2.Event

  "WIO.Checkpoint" - {

    "ignore wrapped logic when it was checkpointed (effectful)" in {
      val (step1Id, runIoStep1) = TestUtils.runIO

      val wio1     = runIoStep1.checkpointed(
        (_, state) => MyCheckpoint(state),
        (_, ckp) => ckp.state,
      )
      val (_, wf1) = TestUtils.createInstance2(wio1)
      wf1.wakeup()
      assert(wf1.queryState().executed == List(step1Id))

      val (_, runIoStep2) = TestUtils.runIO
      val wio2            = runIoStep2.checkpointed(
        (_, state) => MyCheckpoint(state),
        (_, ckp) => ckp.state,
      )
      val (_, wf2)        = TestUtils.createInstance2(wio2)
      wf2.recover(wf1.getEvents)
      wf2.wakeup()
      assert(wf2.queryState().executed == List(step1Id))
    }

    "ignore wrapped logic when it was checkpointed (pure)" in {
      val (step1Id, step1) = TestUtils.pure

      val wio1     = step1.checkpointed(
        (_, state) => MyCheckpoint(state),
        (_, ckp) => ckp.state,
      )
      val (_, wf1) = TestUtils.createInstance2(wio1)
      wf1.wakeup()
      assert(wf1.queryState().executed == List(step1Id))

      val (_, runIoStep2) = TestUtils.runIO
      val wio2            = runIoStep2.checkpointed(
        (_, state) => MyCheckpoint(state),
        (_, ckp) => ckp.state,
      )
      val (_, wf2)        = TestUtils.createInstance2(wio2)
      wf2.recover(wf1.getEvents)
      wf2.wakeup()
      assert(wf2.queryState().executed == List(step1Id))
    }

    "allow recovering when checkpointed logic was removed" in {
      val (stepId, runIoStep) = TestUtils.runIO

      val wio1     = runIoStep.checkpointed(
        (_, state) => MyCheckpoint(state),
        (_, ckp) => ckp.state,
      )
      val (_, wf1) = TestUtils.createInstance2(wio1)
      wf1.wakeup()
      assert(wf1.queryState().executed == List(stepId))

      val wio2             = WIO.recover((_, evt: MyCheckpoint) => evt.state)
      val (_, wf2)         = TestUtils.createInstance2(wio2)
      // Only recover from checkpoint events - the intermediate events are not needed
      // when the checkpointed logic has been removed
      val checkpointEvents = wf1.getEvents.collect { case e: MyCheckpoint => e }
      wf2.recover(checkpointEvents)
      assert(wf2.queryState().executed == List(stepId))
    }

    "ignore checkpointing in case of an error (pure)" in {
      val (error, step1) = TestUtils.error
      val errHandler     = TestUtils.errorHandler

      val wio1 = step1
        .checkpointed(
          (_, state) => MyCheckpoint(state),
          (_, ckp) => ckp.state,
        )
        .handleErrorWith(errHandler)

      val (_, wf1) = TestUtils.createInstance2(wio1)
      wf1.wakeup()
      assert(wf1.queryState().executed == List())
      assert(wf1.queryState().errors == List(error))
    }
    "ignore checkpointing in case of an error (effectful)" in {
      val (error, step1) = TestUtils.errorIO
      val errHandler     = TestUtils.errorHandler

      val wio1 = step1
        .checkpointed(
          (_, state) => MyCheckpoint(state),
          (_, ckp) => ckp.state,
        )
        .handleErrorWith(errHandler)

      val (_, wf1) = TestUtils.createInstance2(wio1)
      wf1.wakeup()
      assert(wf1.queryState().executed == List())
      assert(wf1.queryState().errors == List(error))
    }
  }

}
