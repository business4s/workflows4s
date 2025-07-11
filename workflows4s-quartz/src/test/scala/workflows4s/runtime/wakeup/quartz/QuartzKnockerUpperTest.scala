package workflows4s.runtime.wakeup.quartz

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import com.typesafe.scalalogging.StrictLogging
import org.quartz.Scheduler
import org.quartz.impl.StdSchedulerFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually.{PatienceConfig, eventually}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import workflows4s.testing.TestUtils

import java.time.Instant

class QuartzKnockerUpperTest extends AnyFreeSpec with Matchers with BeforeAndAfterAll with StrictLogging {

  val (dispatcher, releaseDispatcher) = Dispatcher.parallel[IO].allocated.unsafeRunSync()

  "QuartzKnockerUpper" - {

    "should schedule a wakeup at a specified time" in withQuartzKnockerUpper { knockerUpper =>
      var wokenUpAt: Instant                = null
      val testId                            = TestUtils.randomWfId()
      knockerUpper
        .initialize(id =>
          IO {
            if id == testId then {
              wokenUpAt = Instant.now()
              logger.info(s"Woken up at $wokenUpAt")
            }
          },
        )
        .unsafeRunSync()
      val wakeupAt                          = Instant.now().plusMillis(100) // Using a shorter delay since tests show it's sufficient
      logger.info(s"Scheduling wakeup at $wakeupAt")
      knockerUpper.updateWakeup(testId, Some(wakeupAt)).unsafeRunSync()
      implicit val patience: PatienceConfig = PatienceConfig(
        timeout = Span(2, Seconds), // Reduced timeout but still sufficient
        interval = Span(50, Millis),
      )
      eventually {
        assert(wokenUpAt != null, "wokenUpAt is still null")
        assert(wokenUpAt.isAfter(wakeupAt), s"wokenUpAt ($wokenUpAt) is not after wakeupAt ($wakeupAt)")
      }
    }

    "should allow rescheduling a wakeup" in withQuartzKnockerUpper { knockerUpper =>
      var wokenUpAt: Instant = null
      val testId             = TestUtils.randomWfId()
      knockerUpper.initialize(id => IO { if id == testId then wokenUpAt = Instant.now() }).unsafeRunSync()
      val wakeupAt1          = Instant.now().plusMillis(100)
      val wakeupAt2          = Instant.now().plusMillis(200)
      knockerUpper.updateWakeup(testId, Some(wakeupAt1)).unsafeRunSync()
      knockerUpper.updateWakeup(testId, Some(wakeupAt2)).unsafeRunSync()
      Thread.sleep(200)
      eventually {
        assert(wokenUpAt != null)
        assert(wokenUpAt.isAfter(wakeupAt2))
      }
    }

    "should allow canceling a wakeup" in withQuartzKnockerUpper { knockerUpper =>
      var wokenUpAt: Instant = null
      val testId             = TestUtils.randomWfId()
      knockerUpper
        .initialize(id =>
          IO {
            if id == testId then wokenUpAt = Instant.now()
          },
        )
        .unsafeRunSync()
      val wakeupAt1          = Instant.now().plusMillis(100)
      knockerUpper.updateWakeup(testId, Some(wakeupAt1)).unsafeRunSync()
      knockerUpper.updateWakeup(testId, None).unsafeRunSync()
      eventually {
        assert(wokenUpAt == null)
      }
    }

    "should fail to start if wakeup logic is already set" in withQuartzKnockerUpper { knockerUpper =>
      knockerUpper.initialize(_ => IO.unit).unsafeRunSync()
      val secondAttempt = knockerUpper.initialize(_ => IO.unit).attempt.unsafeRunSync()
      assert(secondAttempt.isLeft)
    }
  }

  def withDispatcher(testCode: Dispatcher[IO] => Any) = {
    val (dispatcher, releaseDispatcher) = Dispatcher.parallel[IO].allocated.unsafeRunSync()
    try testCode(dispatcher)
    finally releaseDispatcher.unsafeRunSync()
  }

  def withQuartzScheduler(testCode: Scheduler => Any) = {
    val scheduler = StdSchedulerFactory.getDefaultScheduler
    scheduler.start()
    try {
      testCode(scheduler)
    } finally scheduler.shutdown()
  }

  def withQuartzKnockerUpper(testCode: QuartzKnockerUpper => Any) = {
    withDispatcher(dispatcher => {
      withQuartzScheduler(scheduler => {
        testCode(new QuartzKnockerUpper(scheduler, dispatcher))
      })
    })
  }

}
