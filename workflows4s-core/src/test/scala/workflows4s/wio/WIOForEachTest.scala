package workflows4s.wio

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{EitherValues, OptionValues}
import workflows4s.testing.TestUtils
import workflows4s.wio.internal.WorkflowEmbedding

class WIOForEachTest extends AnyFreeSpec with Matchers with OptionValues with EitherValues {

  import TestCtx2.*

  "WIO.forEach" - {

    "should execute forEach elements and combine their results" in {
      val (step1Id, step1)               = TestUtils.pure
      val elements @ List(el1, el2, el3) = genElements(3)

      val (forEachStepId, wf) = createForEach(step1)

      val (_, instance) = TestUtils.createInstance2(wf.provideInput(elements.toSet))
      val resultState   = instance.queryState()

      assert(
        resultState.executed == List(
          el1.prefixedWith(el1),
          step1Id.prefixedWith(el1),
          el2.prefixedWith(el2),
          step1Id.prefixedWith(el2),
          el3.prefixedWith(el3),
          step1Id.prefixedWith(el3),
          forEachStepId,
        ),
      )
      assert(resultState.errors.isEmpty)
    }

    "should complete immediately with empty element list" in {
      val (step1Id, step1)    = TestUtils.pure
      val (forEachStepId, wf) = createForEach(step1)

      val (_, instance) = TestUtils.createInstance2(wf.provideInput(Set()))
      val resultState   = instance.queryState()

      assert(resultState.executed == List(forEachStepId))
    }

    "should handle forEach execution with one element failing" in {
      val (err, errorStep)          = TestUtils.error
      val (forEachStepId, forEach)  = createForEach(errorStep)
      val elements @ List(el1, el2) = genElements(2)

      val errHandler = TestUtils.errorHandler
      val wf         = forEach.handleErrorWith(errHandler)

      val (_, instance) = TestUtils.createInstance2(wf.provideInput(elements.toSet))
      val resultState   = instance.queryState()

      // Should handle the error appropriately
      assert(resultState.errors == List(err))
      assert(resultState.executed.isEmpty)
    }

    "should wait for all signals in forEach elements" in {
      val (signalDef, signalStepId, signalStep) = TestUtils.signal
      val (forEachStepId, wf)                   = createForEach(signalStep)
      val elements @ List(el1, el2)             = genElements(2)

      val (_, instance) = TestUtils.createInstance2(wf.provideInput(elements.toSet))
      assert(instance.queryState().executed == List())

      val unroutedResp = instance.deliverSignal(signalDef, 1)
      assert(unroutedResp.isLeft)

      val response1 = instance.deliverRoutedSignal(SigRouter, el2, signalDef, 1).value
      assert(response1 == 1)
      assert(
        instance.queryState().executed == List(
          el2.prefixedWith(el2),
          signalStepId.prefixedWith(el2),
        ),
      )

      val response2 = instance.deliverRoutedSignal(SigRouter, el1, signalDef, 2).value
      assert(response2 == 2)
      assert(
        instance.queryState().executed == List(
          el1.prefixedWith(el1),
          signalStepId.prefixedWith(el1),
          el2.prefixedWith(el2),
          signalStepId.prefixedWith(el2),
          forEachStepId,
        ),
      )
    }

    "should wait for all timers in forEach elements" in {
      val (duration, timerStep)     = TestUtils.timer(secs = 1)
      val (pureId, pureStep)        = TestUtils.pure
      val elements @ List(el1, el2) = genElements(2)

      val (forEachStepId, wf) = createForEach(timerStep >>> pureStep)

      val (clock, instance) = TestUtils.createInstance2(wf.provideInput(elements.toSet))
      assert(instance.queryState().executed == List())

      instance.wakeup()
      clock.advanceBy(duration)
      instance.wakeup()

      val state = instance.queryState()
      assert(
        state.executed == List(
          el1.prefixedWith(el1),
          pureId.prefixedWith(el1),
          el2.prefixedWith(el2),
          pureId.prefixedWith(el2),
          forEachStepId,
        ),
      )
    }

  }

  type Elem = StepId
  def createForEach[Err](
      elemFlow: TestCtx2.WIO[TestState, Err, TestState],
  ): (StepId, TestCtx2.WIO[Set[Elem], Err, TestState]) = {
    val elemFlowAdjusted = elemFlow.transformInput[Elem](elem => TestState.empty.addExecuted(elem))
    val finishedStepId   = StepId.random
    val wf               = TestCtx2.WIO
      .forEach[Set[StepId]](identity)
      .execute[TestCtx2.Ctx](elemFlowAdjusted, TestState.empty)
      .withEventsEmbeddedThrough(evtEmbedding)
      .withInterimState(_ => TestState.empty)
      .incorporatingChangesThrough((elem, elemState, interimState) => elemState.prefixWith(elem) ++ interimState)
      .withOutputBuiltWith((_, results) =>
        results.map((elem, state) => state.prefixWith(elem)).reduceOption(_ ++ _).getOrElse(TestState.empty).addExecuted(finishedStepId),
      )
      .withSignalsWrappedWith(SigRouter)
      .autoNamed()
    (finishedStepId, wf)
  }

  case class ForEachTestEvent(elem: Elem, event: TestCtx2.Event) extends TestCtx2.Event
  val evtEmbedding = new WorkflowEmbedding.Event[(Elem, TestCtx2.Event), TestCtx2.Event] {
    override def convertEvent(e: (Elem, TestCtx2.Event)): TestCtx2.Event           = ForEachTestEvent(e._1, e._2)
    override def unconvertEvent(e: TestCtx2.Event): Option[(Elem, TestCtx2.Event)] = e match {
      case ForEachTestEvent(elem, event) => Some((elem, event))
      case _                             => None
    }
  }

  object SigRouter extends SimpleSignalRouter[Elem]

  def genElements(n: Int): List[Elem] = List.fill(n)(StepId.random).zipWithIndex.map { case (id, i) => id.prefixedWith(s"elem$i") }

}
