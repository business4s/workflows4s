package workflows4s.wio

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{EitherValues, OptionValues}
import workflows4s.wio.model.{WIOMeta, WIOModel}

class WIODraftTest extends AnyFreeSpec with Matchers with OptionValues with EitherValues {

  import TestCtx2.*

  "WIO.draft" - {
    "should create a sequence of steps with correct names" in {
      val step1: WIO.Draft = WIO.draft.step("readCSVFile")
      val step2            = WIO.draft.step("parseCSVFile")
      val wio              = step1 >>> step2
      val model            = wio.toProgress.toModel
      model.toEmptyProgress

      assert(
        model == WIOModel.Sequence(
          List(
            WIOModel.RunIO(WIOMeta.RunIO(Some("readCSVFile"), None, None)),
            WIOModel.RunIO(WIOMeta.RunIO(Some("parseCSVFile"), None, None)),
          ),
        ),
      )
    }

    "should create a sequence of steps with auto-generated names when not provided" in {
      val step1: WIO.Draft = WIO.draft.step()
      val step2            = WIO.draft.step()
      val wio              = step1 >>> step2
      val model            = wio.toProgress.toModel

      assert(
        model == WIOModel.Sequence(
          List(
            WIOModel.RunIO(WIOMeta.RunIO(Some("Step1"), None, None)),
            WIOModel.RunIO(WIOMeta.RunIO(Some("Step2"), None, None)),
          ),
        ),
      )
    }

    "should create a sequence of steps with error messages when provided" in {
      val step1: WIO.Draft = WIO.draft.step("readCSVFile", error = "path not found")
      val step2            = WIO.draft.step("parseCSVFile", error = "File format not supported")
      val wio              = step1 >>> step2
      val model            = wio.toProgress.toModel

      assert(
        model == WIOModel.Sequence(
          List(
            WIOModel.RunIO(WIOMeta.RunIO(Some("readCSVFile"), Some(WIOMeta.Error("path not found")), None)),
            WIOModel.RunIO(WIOMeta.RunIO(Some("parseCSVFile"), Some(WIOMeta.Error("File format not supported")), None)),
          ),
        ),
      )
    }

    "should create a signal step with correct name" in {
      val signal: WIO.Draft = WIO.draft.signal("CR Approved")
      val model             = signal.toProgress.toModel

      model match {
        case WIOModel.HandleSignal(handleSigMeta) =>
          handleSigMeta.signalName shouldBe "CR Approved"
          handleSigMeta.error shouldBe None
        case _                                    => fail("Expected HandleSignal model")
      }
    }

    "should create a sequence with both step and signal" in {
      val step: WIO.Draft   = WIO.draft.step("TransformData")
      val signal: WIO.Draft = WIO.draft.signal("run migration")
      val wio               = step >>> signal
      val model             = wio.toProgress.toModel

      model match {
        case WIOModel.Sequence(seqSteps) =>
          seqSteps.length shouldBe 2
          seqSteps.head shouldBe a[WIOModel.RunIO]
          seqSteps.head.asInstanceOf[WIOModel.RunIO].meta.name shouldBe Some("TransformData")
          seqSteps(1) shouldBe a[WIOModel.HandleSignal]
          seqSteps(1).asInstanceOf[WIOModel.HandleSignal].meta.signalName shouldBe "run migration"
        case _                           => fail("Expected Sequence model")
      }
    }

    "should create a fork with correct branches" in {
      val approve = WIO.draft.step("Approve")
      val reject  = WIO.draft.step("Reject")
      val wio     = WIO.draft.choice("Review")(
        "Approved" -> approve,
        "Rejected" -> reject,
      )
      val model   = wio.toProgress.toModel

      model match {
        case WIOModel.Fork(branches, meta) =>
          meta.name shouldBe Some("Review")
          branches.length shouldBe 2

          branches.head shouldBe WIOModel.RunIO(WIOMeta.RunIO(Some("Approve"), None, None))
          branches(1) shouldBe WIOModel.RunIO(WIOMeta.RunIO(Some("Reject"), None, None))
        case _                             => fail("Expected Fork model")
      }
    }

    "should create a parallel step with multiple elements" in {
      val step1: WIO.Draft    = WIO.draft.step("task1")
      val step2: WIO.Draft    = WIO.draft.step("task2")
      val step3: WIO.Draft    = WIO.draft.step("task3")
      val parallel: WIO.Draft = WIO.draft.parallel(step1, step2, step3)
      val model               = parallel.toProgress.toModel

      model match {
        case WIOModel.Parallel(parElements) =>
          parElements.length shouldBe 3
          parElements(0) shouldBe WIOModel.RunIO(WIOMeta.RunIO(Some("task1"), None, None))
          parElements(1) shouldBe WIOModel.RunIO(WIOMeta.RunIO(Some("task2"), None, None))
          parElements(2) shouldBe WIOModel.RunIO(WIOMeta.RunIO(Some("task3"), None, None))
        case _                              => fail("Expected Parallel model")
      }
    }
  }
}
