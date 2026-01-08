package workflows4s.cats

import cats.effect.IO
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import workflows4s.runtime.instanceengine.{Effect, EffectTestSuite, Outcome}

class CatsEffectTest extends AnyFreeSpec with EffectTestSuite[IO] with Matchers {

  import CatsEffect.ioEffect
  given effect: Effect[IO] = Effect[IO]

  "CatsEffect (IO)" - {
    effectTests()

    // Effect-specific tests below

    "fiber cancellation" in {
      import cats.effect.kernel.Deferred

      val program = for {
        deferred <- Deferred[IO, Unit]
        fiber    <- E.start {
                      // This will never complete naturally
                      deferred.get *> E.pure(42)
                    }
        _        <- fiber.cancel
        outcome  <- fiber.join
      } yield outcome

      assert(effect.runSyncUnsafe(program) == Outcome.Canceled)
    }
  }
}
