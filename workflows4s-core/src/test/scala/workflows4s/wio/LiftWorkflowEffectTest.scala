package workflows4s.wio

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Try

class LiftWorkflowEffectTest extends AnyFreeSpec with Matchers {

  object CtxIO1 extends WorkflowContext {
    type Effect[T]      = IO[T]
    override type Event = String
    override type State = Int
  }

  object CtxIO2 extends WorkflowContext {
    type Effect[T]      = IO[T]
    override type Event = Boolean
    override type State = Double
  }

  object CtxTry extends WorkflowContext {
    type Effect[T]      = Try[T]
    override type Event = String
    override type State = Int
  }

  "LiftWorkflowEffect" - {

    "should extract effect values from a context" in {
      val lift                                = summon[LiftWorkflowEffect[CtxIO1.Ctx, IO]]
      val wcEffect: WCEffect[CtxIO1.Ctx][Int] = IO(42)
      lift.apply(wcEffect).unsafeRunSync() shouldBe 42
    }

    "should convert between contexts with the same effect type" in {
      val between                             = summon[LiftWorkflowEffect.Between[CtxIO1.Ctx, CtxIO2.Ctx]]
      val wcEffect: WCEffect[CtxIO1.Ctx][Int] = IO(42)
      val lifted                              = between.apply(wcEffect)

      val extract = summon[LiftWorkflowEffect[CtxIO2.Ctx, IO]]
      extract.apply(lifted).unsafeRunSync() shouldBe 42
    }

    "should perform real conversion through a different effect type" in {
      val liftTryToIO: LiftWorkflowEffect[CtxTry.Ctx, IO] =
        LiftWorkflowEffect.through[CtxTry.Ctx, Try]([A] => (fa: Try[A]) => IO.fromTry(fa))

      val wcEffect: WCEffect[CtxTry.Ctx][Int] = Try(42)
      val result                              = liftTryToIO.apply(wcEffect)
      result.unsafeRunSync() shouldBe 42
    }

    "should not compile Between for contexts with different effect types" in {
      assertDoesNotCompile("summon[LiftWorkflowEffect.Between[CtxIO1.Ctx, CtxTry.Ctx]]")
    }
  }
}
