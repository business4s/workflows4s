package workflows4s.wio

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{EitherValues, OptionValues}
import workflows4s.testing.{TestRuntime, TestUtils}

import java.time.Duration

class WIORetryTest extends AnyFreeSpec with Matchers with OptionValues with EitherValues {

  "WIO.retry" - {

    "should successfully wake up and schedule another wakeup when retry was triggered" in {
      val runtime         = TestRuntime()
      val exception       = new RuntimeException("fail")
      val (_, failingWIO) = TestUtils.runIOFailing(exception)

      val failingInstance   = runtime.createInstance(failingWIO)
      val receivedException = intercept[RuntimeException] {
        failingInstance.wakeup()
      }
      assert(receivedException == exception)

      val retryDelay       = Duration.ofSeconds(123)
      val retryingWIO      = failingWIO.retry.statelessly.wakeupIn({ case _ => retryDelay })
      val retryingInstance = runtime.createInstance(retryingWIO)

      assert(runtime.knockerUpper.lastRegisteredWakeupUnsafe(failingInstance.id) == None)
      assert(runtime.knockerUpper.lastRegisteredWakeupUnsafe(retryingInstance.id) == None)
      retryingInstance.wakeup()
      assert(runtime.knockerUpper.lastRegisteredWakeupUnsafe(retryingInstance.id) == Some(runtime.clock.instant.plus(retryDelay)))
    }

    "should rethrow when onError returns None" in {
      val runtime         = TestRuntime()
      val exception       = new RuntimeException("fail")
      val (_, failingWIO) = TestUtils.runIOFailing(exception)

      // Id effect - just return None directly
      val retryingWIO = failingWIO.retry.statelessly.wakeupAt((_, _, _) => None)
      val instance    = runtime.createInstance(retryingWIO)

      assert(runtime.knockerUpper.lastRegisteredWakeupUnsafe(instance.id) == None)
      val receivedException = intercept[RuntimeException] {
        instance.wakeup()
      }
      assert(receivedException == exception)
      assert(runtime.knockerUpper.lastRegisteredWakeupUnsafe(instance.id) == None)
    }

    "when another wakeup is present" - {

      "should not overwrite earlier one" in {
        val runtime         = TestRuntime()
        val exception       = new RuntimeException("fail")
        val (_, failingWIO) = TestUtils.runIOFailing(exception)

        val retryDelay  = Duration.ofSeconds(123)
        val retryingWIO = failingWIO.retry.statelessly.wakeupIn({ case _ => retryDelay })

        val interruptionDelay = retryDelay.minusSeconds(1)
        val interruption      = TestUtils.timer(interruptionDelay.getSeconds.toInt)._2.toInterruption
        val interruptedWIO    = retryingWIO.interruptWith(interruption)
        val instance          = runtime.createInstance(interruptedWIO)

        // wakeup didn't throw but scheduled wakeup
        instance.wakeup()
        assert(runtime.knockerUpper.lastRegisteredWakeupUnsafe(instance.id) == Some(runtime.clock.instant.plus(interruptionDelay)))
      }

      "should overwrite later one" in {
        val runtime         = TestRuntime()
        val exception       = new RuntimeException("fail")
        val (_, failingWIO) = TestUtils.runIOFailing(exception)

        val retryDelay  = Duration.ofSeconds(123)
        val retryingWIO = failingWIO.retry.statelessly.wakeupIn({ case _ => retryDelay })

        val interruptionDelay = retryDelay.plusSeconds(1)
        val interruption      = TestUtils.timer(interruptionDelay.getSeconds.toInt)._2.toInterruption
        val interruptedWIO    = retryingWIO.interruptWith(interruption)
        val instance          = runtime.createInstance(interruptedWIO)

        // wakeup didn't throw but scheduled wakeup
        instance.wakeup()
        assert(runtime.knockerUpper.lastRegisteredWakeupUnsafe(instance.id) == Some(runtime.clock.instant.plus(retryDelay)))
      }
    }

    "stateful retry" - {

      "should track retry count and eventually give up after max retries" in {
        val runtime         = TestRuntime()
        val exception       = new RuntimeException("fail")
        val (_, failingWIO) = TestUtils.runIOFailing(exception)

        case class RetryEvent(attemptNumber: Int) extends TestCtx2.Event
        val maxRetries = 3
        val retryDelay = Duration.ofSeconds(10)

        val retryingWIO = failingWIO.retry
          .usingState[Int]
          .onError { (_, _, _, retryState) =>
            val attempt = retryState.getOrElse(0)
            if attempt < maxRetries then workflows4s.wio.WIO.Retry.Stateful.Result.ScheduleWakeup(
              runtime.clock.instant.plus(retryDelay),
              Some(RetryEvent(attempt + 1)),
            )
            else
              workflows4s.wio.WIO.Retry.Stateful.Result.Ignore
          }
          .handleEventsWith { (_, evt: RetryEvent, _, _) =>
            Left(evt.attemptNumber) // Continue retrying with updated count
          }

        val instance = runtime.createInstance(retryingWIO)
        // With greedy evaluation, retries until maxRetries exhausted then throws
        val thrown   = intercept[RuntimeException] {
          instance.wakeup()
        }
        assert(thrown == exception)
      }

      "should recover with event when Recover result is returned" in {
        val runtime         = TestRuntime()
        val exception       = new RuntimeException("fail")
        val (_, failingWIO) = TestUtils.runIOFailing(exception)

        case class RecoveryEvent(recovered: Boolean) extends TestCtx2.Event

        val retryingWIO = failingWIO.retry
          .usingState[Unit]
          .onError { (_, _, _, _) =>
            workflows4s.wio.WIO.Retry.Stateful.Result.Recover(RecoveryEvent(true))
          }
          .handleEventsWith { (_, _: RecoveryEvent, state, _) =>
            Right(Right(state.asInstanceOf[TestState])) // Recover successfully
          }

        val instance = runtime.createInstance(retryingWIO)
        // Should not throw - recovers via event
        instance.wakeup()
        // No wakeup scheduled since we recovered
        assert(runtime.knockerUpper.lastRegisteredWakeupUnsafe(instance.id) == None)
      }
    }
  }
}
