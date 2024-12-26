package workflows4s.runtime.wakeup

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.freespec.AnyFreeSpec

import java.time.Instant
import scala.concurrent.duration.*

class SleepingKnockerUpperTest extends AnyFreeSpec {

  "SleepingKnockerUpper" - {

    "should successfully schedule and perform a wakeup" in {
      val id = "task1"

      var wokenUp                         = Vector[String]()
      val wakeUpLogic: String => IO[Unit] = id => IO { wokenUp = wokenUp.appended(id) }

      SleepingKnockerUpper
        .create[String]()
        .use { ku =>
          for {
            _  <- ku.initialize(wakeUpLogic)
            now = Instant.now()
            _  <- ku.updateWakeup(id, Some(now.plusMillis(100)))
            _  <- IO.sleep(200.millis)
          } yield ku
        }
        .unsafeRunSync()
      assert(wokenUp == Vector(id))
    }

    "should cancel a scheduled wakeup when updateWakeup is called with None" in {
      val id                              = "task2"
      var wokenUp                         = false
      val wakeUpLogic: String => IO[Unit] = _ => IO { wokenUp = true }
      SleepingKnockerUpper
        .create[String]()
        .use { ku =>
          for {
            _  <- ku.initialize(wakeUpLogic)
            now = Instant.now()
            _  <- ku.updateWakeup(id, Some(now.plusMillis(100)))
            _  <- ku.updateWakeup(id, None)
            _  <- IO.sleep(200.millis)
          } yield ()
        }
        .unsafeRunSync()

      assert(!wokenUp)
    }

    "should throw an exception if trying to initialize wakeupLogic twice" in {
      val wakeUpLogic: String => IO[Unit] = _ => IO.unit

      val test = SleepingKnockerUpper
        .create[String]()
        .use { ku =>
          for {
            _   <- ku.initialize(wakeUpLogic)
            res <- ku.initialize(wakeUpLogic)
          } yield res
        }
        .attempt
        .unsafeRunSync()
      assert(test.isLeft)
    }
  }
}
