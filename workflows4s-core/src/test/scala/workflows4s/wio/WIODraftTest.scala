package workflows4s.wio

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{EitherValues, OptionValues}
import workflows4s.wio.WIO.Draft
import workflows4s.wio.model.{WIOMeta, WIOModel}

class WIODraftTest extends AnyFreeSpec with Matchers with OptionValues with EitherValues {

  import TestCtx2.*

  "WIO.draft" - {
    "should create a sequence of steps with correct names" in {
      val step1: Draft[Ctx] = WIO.draft.step("readCSVFile")
      val step2             = WIO.draft.step("parseCSVFile")
      val wio               = step1 >>> step2
      val model             = wio.toProgress.toModel
      model.toEmptyProgress

      assert(
        model == WIOModel.Sequence(
          List(
            WIOModel.RunIO(WIOMeta.RunIO(Some("readCSVFile"), None)),
            WIOModel.RunIO(WIOMeta.RunIO(Some("parseCSVFile"), None)),
          ),
        ),
      )
    }

    "should create a sequence of steps with auto-generated names when not provided" in {
      val step1: Draft[Ctx] = WIO.draft.step()
      val step2             = WIO.draft.step()
      val wio               = step1 >>> step2
      val model             = wio.toProgress.toModel

      assert(
        model == WIOModel.Sequence(
          List(
            WIOModel.RunIO(WIOMeta.RunIO(Some("Step1"), None)),
            WIOModel.RunIO(WIOMeta.RunIO(Some("Step2"), None)),
          ),
        ),
      )
    }

    "should create a sequence of steps with error messages when provided" in {
      val step1: Draft[Ctx] = WIO.draft.step("readCSVFile", error = "path not found")
      val step2             = WIO.draft.step("parseCSVFile", error = "File format not supported")
      val wio               = step1 >>> step2
      val model             = wio.toProgress.toModel

      assert(
        model == WIOModel.Sequence(
          List(
            WIOModel.RunIO(WIOMeta.RunIO(Some("readCSVFile"), Some(WIOMeta.Error("path not found")))),
            WIOModel.RunIO(WIOMeta.RunIO(Some("parseCSVFile"), Some(WIOMeta.Error("File format not supported")))),
          ),
        ),
      )
    }

    "should create a signal step with correct name" in {
      val signal: Draft[Ctx] = WIO.draft.signal("CR Approved")
      val model              = signal.toProgress.toModel

      model match {
        case WIOModel.HandleSignal(meta) =>
          meta.signalName shouldBe "CR Approved"
          meta.error shouldBe None
        case _                           => fail("Expected HandleSignal model")
      }
    }

    "should create a sequence with both step and signal" in {
      val step: Draft[Ctx]   = WIO.draft.step("TransformData")
      val signal: Draft[Ctx] = WIO.draft.signal("run migration")
      val wio                = step >>> signal
      val model              = wio.toProgress.toModel

      model match {
        case WIOModel.Sequence(steps) =>
          steps.length shouldBe 2
          steps.head shouldBe a[WIOModel.RunIO]
          steps.head.asInstanceOf[WIOModel.RunIO].meta.name shouldBe Some("TransformData")
          steps(1) shouldBe a[WIOModel.HandleSignal]
          steps(1).asInstanceOf[WIOModel.HandleSignal].meta.signalName shouldBe "run migration"
        case _                        => fail("Expected Sequence model")
      }
    }
  }
}
