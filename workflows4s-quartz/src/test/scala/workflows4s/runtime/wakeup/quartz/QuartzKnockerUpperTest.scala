package workflows4s.runtime.wakeup.quartz

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import org.quartz.Scheduler
import org.quartz.impl.StdSchedulerFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually.eventually
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import workflows4s.runtime.wakeup.quartz.QuartzKnockerUpper.RuntimeId

import java.time.Instant
import java.util.UUID

class QuartzKnockerUpperTest extends AnyFreeSpec with Matchers with BeforeAndAfterAll {

  val (dispatcher, releaseDispatcher) = Dispatcher.parallel[IO].allocated.unsafeRunSync()

  "QuartzKnockerUpper" - {

    "should schedule a wakeup at a specified time" in withQuartzKnockerUpper { knockerUpper =>
      var wokenUpAt: Instant = null
      val testId             = "test-id"
      knockerUpper.initialize(id => IO { if (id == testId) wokenUpAt = Instant.now() }).unsafeRunSync()
      val wakeupAt           = Instant.now().plusMillis(100)
      knockerUpper.updateWakeup(testId, Some(wakeupAt)).unsafeRunSync()
      eventually {
        assert(wokenUpAt != null && wokenUpAt.isAfter(wakeupAt))
      }
    }

    "should allow rescheduling a wakeup" in withQuartzKnockerUpper { knockerUpper =>
      var wokenUpAt: Instant = null
      val testId             = "test-id"
      knockerUpper.initialize(id => IO { if (id == testId) wokenUpAt = Instant.now() }).unsafeRunSync()
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
      val testId             = "test-id"
      knockerUpper
        .initialize(id =>
          IO {
            if (id == testId) wokenUpAt = Instant.now()
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

  def withQuartzKnockerUpper(testCode: QuartzKnockerUpper[String] => Any) = {
    withDispatcher(dispatcher => {
      withQuartzScheduler(scheduler => {
        testCode(new QuartzKnockerUpper[String](RuntimeId(UUID.randomUUID().toString), scheduler, dispatcher))
      })
    })
  }

}
