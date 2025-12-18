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

      assert(runtime.knockerUpper.lastRegisteredWakeup(failingInstance.id) == None)
      assert(runtime.knockerUpper.lastRegisteredWakeup(retryingInstance.id) == None)
      retryingInstance.wakeup()
      assert(runtime.knockerUpper.lastRegisteredWakeup(retryingInstance.id) == Some(runtime.clock.instant.plus(retryDelay)))
    }

    "should rethrow when onError returns None" in {
      val runtime         = TestRuntime()
      val exception       = new RuntimeException("fail")
      val (_, failingWIO) = TestUtils.runIOCustom(IO.raiseError(exception))

      val retryingWIO = failingWIO.retry((_, _, _) => None)
      val instance    = runtime.createInstance(retryingWIO)

      assert(runtime.knockerUpper.lastRegisteredWakeup(instance.id) == None)
      val receivedException = intercept[RuntimeException] {
        instance.wakeup()
      }
      assert(receivedException == exception)
      assert(runtime.knockerUpper.lastRegisteredWakeup(instance.id) == None)
    }

    "when another wakeup is present" - {

      "should not overwrite earlier one" in {
        val runtime         = TestRuntime()
        val exception       = new RuntimeException("fail")
        val (_, failingWIO) = TestUtils.runIOCustom(IO.raiseError(exception))

        val retryDelay  = Duration.ofSeconds(123)
        val retryingWIO = failingWIO.retryIn({ case _ => retryDelay })

        val interruptionDelay = retryDelay.minusSeconds(1)
        val interruption      = TestUtils.timer(interruptionDelay.getSeconds.toInt)._2.toInterruption
        val interruptedWIO    = retryingWIO.interruptWith(interruption)
        val instance          = runtime.createInstance(interruptedWIO)

        // wakeup didnt throw but wakeup
        instance.wakeup()
        assert(runtime.knockerUpper.lastRegisteredWakeup(instance.id) == Some(runtime.clock.instant.plus(interruptionDelay)))

      }
      "should overwrite later one" in {
        val runtime         = TestRuntime()
        val exception       = new RuntimeException("fail")
        val (_, failingWIO) = TestUtils.runIOCustom(IO.raiseError(exception))

        val retryDelay  = Duration.ofSeconds(123)
        val retryingWIO = failingWIO.retryIn({ case _ => retryDelay })

        val interruptionDelay = retryDelay.plusSeconds(1)
        val interruption      = TestUtils.timer(interruptionDelay.getSeconds.toInt)._2.toInterruption
        val interruptedWIO    = retryingWIO.interruptWith(interruption)
        val instance          = runtime.createInstance(interruptedWIO)

        // wakeup didnt throw but wakeup
        instance.wakeup()
        assert(runtime.knockerUpper.lastRegisteredWakeup(instance.id) == Some(runtime.clock.instant.plus(retryDelay)))

      }
    }

  }
}
