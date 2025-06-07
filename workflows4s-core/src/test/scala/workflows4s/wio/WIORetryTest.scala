package workflows4s.wio

import cats.effect.IO
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{EitherValues, OptionValues}
import workflows4s.testing.{TestRuntime, TestUtils}

import java.time.Duration

class WIORetryTest extends AnyFreeSpec with Matchers with OptionValues with EitherValues {

  case class RunIODone(stepId: StepId) extends TestCtx2.Event

  "WIO.retry" - {

    "should successfully wake up and schedule another wakeup when retry was triggered" in {
      val runtime         = TestRuntime()
      val exception       = new RuntimeException("fail")
      val (_, failingWIO) = TestUtils.runIOCustom(IO.raiseError(exception))

      val failingInstance   = runtime.createInstance(failingWIO)
      val receivedException = intercept[RuntimeException] {
        failingInstance.wakeup()
      }
      assert(receivedException == exception)

      val retryDelay       = Duration.ofSeconds(123)
      val retryingWIO      = failingWIO.retryIn({ case _ => retryDelay })
      val retryingInstance = runtime.createInstance(retryingWIO)

      assert(runtime.knockerUpper.lastRegisteredWakeup == None)
      retryingInstance.wakeup()
      assert(runtime.knockerUpper.lastRegisteredWakeup == Some(runtime.clock.instant.plus(retryDelay)))
    }

    "should rethrow when onError returns None" in {
      val runtime         = TestRuntime()
      val exception       = new RuntimeException("fail")
      val (_, failingWIO) = TestUtils.runIOCustom(IO.raiseError(exception))

      val retryingWIO      = failingWIO.retry((_, _, _) => IO(None))
      val retryingInstance = runtime.createInstance(retryingWIO)

      assert(runtime.knockerUpper.lastRegisteredWakeup == None)
      val receivedException = intercept[RuntimeException] {
        retryingInstance.wakeup()
      }
      assert(receivedException == exception)
      assert(runtime.knockerUpper.lastRegisteredWakeup == None)
    }

  }
}
