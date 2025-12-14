package workflows4s.wio

import cats.effect.IO
import cats.implicits.catsSyntaxOptionId
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{EitherValues, OptionValues}
import workflows4s.testing.{TestRuntime, TestUtils}

import java.time.{Duration, Instant}

class WIORetryTest extends AnyFreeSpec with Matchers with OptionValues with EitherValues {

  case class RunIODone(stepId: StepId) extends TestCtx2.Event

  "WIO.retry" - {

    "stateless" - {

      "should successfully wake up and schedule another wakeup when retry was triggered" in new Fixture {

        val failingInstance   = runtime.createInstance(failingWIO)
        val receivedException = intercept[RuntimeException](failingInstance.wakeup())
        assert(receivedException == exception)

        val retryDelay       = Duration.ofSeconds(123)
        val retryingWIO      = failingWIO.retry.statelessly.wakeupIn({ case _ => retryDelay })
        val retryingInstance = runtime.createInstance(retryingWIO)

        assert(runtime.knockerUpper.lastRegisteredWakeup(failingInstance.id) == None)
        assert(runtime.knockerUpper.lastRegisteredWakeup(retryingInstance.id) == None)
        retryingInstance.wakeup()
        assert(runtime.knockerUpper.lastRegisteredWakeup(retryingInstance.id) == Some(runtime.clock.instant.plus(retryDelay)))
      }

      "should rethrow when onError returns None" in new Fixture {
        val retryingWIO = failingWIO.retry.statelessly.wakeupAt((_, _, _) => IO(None))
        val instance    = runtime.createInstance(retryingWIO)

        assert(runtime.knockerUpper.lastRegisteredWakeup(instance.id) == None)
        val receivedException = intercept[RuntimeException] {
          instance.wakeup()
        }
        assert(receivedException == exception)
        assert(runtime.knockerUpper.lastRegisteredWakeup(instance.id) == None)
      }

      "when another wakeup is present" - {

        "should not overwrite earlier one" in new Fixture {

          val retryDelay  = Duration.ofSeconds(123)
          val retryingWIO = failingWIO.retry.statelessly.wakeupIn({ case _ => retryDelay })

          val interruptionDelay = retryDelay.minusSeconds(1)
          val interruption      = TestUtils.timer(interruptionDelay.getSeconds.toInt)._2.toInterruption
          val interruptedWIO    = retryingWIO.interruptWith(interruption)
          val instance          = runtime.createInstance(interruptedWIO)

          // wakeup didnt throw but wakeup
          instance.wakeup()
          assert(runtime.knockerUpper.lastRegisteredWakeup(instance.id) == Some(runtime.clock.instant.plus(interruptionDelay)))

        }
        "should overwrite later one" in new Fixture {

          val retryDelay  = Duration.ofSeconds(123)
          val retryingWIO = failingWIO.retry.statelessly.wakeupIn({ case _ => retryDelay })

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

    "stateful" - {
      "should keep state between executions" in new Fixture {
        case class RetryEvent(inc: Int) extends TestCtx2.Event
        val retryTime   = Instant.now().plus(Duration.ofSeconds(123))
        val retryingWIO = failingWIO.retry
          .usingState[Int]
          .onError[RetryEvent, Nothing]((_, _, _, _) => IO(WIO.Retry.StatefulResult.ScheduleWakeup(retryTime, RetryEvent(1).some)))
          .handleEventsWithTogether((_, event, _, retryState) => {
            val newState = retryState.getOrElse(0) + event.inc
            if newState < 3 then Left(newState)
            else Right(Right(TestState.empty.addError(s"Recovered after $newState")))
          })

        val instance = runtime.createInstance(retryingWIO)

        // wakeup didnt throw but woke up
        instance.wakeup()
        assert(runtime.knockerUpper.lastRegisteredWakeup(instance.id) == Some(retryTime))
        assert(instance.queryState() == TestState.empty.addError(s"Recovered after 3"))
      }
    }
  }

  trait Fixture {
    val runtime         = TestRuntime()
    val exception       = new RuntimeException("fail")
    val (_, failingWIO) = TestUtils.runIOCustom(IO.raiseError(exception))
  }

}
