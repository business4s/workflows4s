package workflows4s.runtime.wakeup

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import workflows4s.cats.CatsEffect
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.runtime.instanceengine.Effect
import workflows4s.testing.TestUtils

import scala.concurrent.duration.*

/** Tests for SleepingKnockerUpper using IO effect.
  *
  * Note: This test is in workflows4s-cats (not workflows4s-core) because it tests the effect-polymorphic SleepingKnockerUpper with a concrete effect
  * type (IO), requiring the Effect[IO] instance from CatsEffect.
  */
class SleepingKnockerUpperTest extends SleepingKnockerUpperTestSuite[IO] {

  given effect: Effect[IO] = CatsEffect.ioEffect

  override def sleep(duration: FiniteDuration): IO[Unit] = IO.sleep(duration)

  override def randomWfId(): WorkflowInstanceId = TestUtils.randomWfId()

  "SleepingKnockerUpper (IO)" - {
    sleepingKnockerUpperTests()
  }
}
