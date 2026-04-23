package workflows4s.runtime.wakeup

import cats.effect.IO
import cats.effect.std.CountDownLatch
import cats.effect.unsafe.implicits.global
import org.scalatest.freespec.AnyFreeSpec
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.testing.TestUtils

import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.*

class SleepingKnockerUpperTest extends AnyFreeSpec {

  "SleepingKnockerUpper" - {

    "should successfully schedule and perform a wakeup" in {
      val id = TestUtils.randomWfId()

      var wokenUp                                     = Vector[WorkflowInstanceId]()
      val wakeUpLogic: WorkflowInstanceId => IO[Unit] = id => IO { wokenUp = wokenUp.appended(id) }

      SleepingKnockerUpper
        .create()
        .use { ku =>
          for {
            _  <- ku.initialize(wakeUpLogic)
            now = Instant.now()
            _  <- ku.updateWakeup(id, Some(now.plusMillis(100)))
            _  <- IO.sleep(200.millis)
          } yield ()
        }
        .unsafeRunSync()
      assert(wokenUp == Vector(id))
    }

    "should cancel a scheduled wakeup when updateWakeup is called with None" in {
      val id                                          = TestUtils.randomWfId()
      var wokenUp                                     = false
      val wakeUpLogic: WorkflowInstanceId => IO[Unit] = _ => IO { wokenUp = true }
      SleepingKnockerUpper
        .create()
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

    "should not self-cancel when wakeup callback reentrantly calls updateWakeup" in {
      val id              = TestUtils.randomWfId()
      val beforeReentrant = new AtomicBoolean(false)
      val afterReentrant  = new AtomicBoolean(false)

      SleepingKnockerUpper
        .create()
        .use { ku =>
          for {
            done           <- CountDownLatch[IO](1)
            wakeUpLogic     = (wfId: WorkflowInstanceId) =>
                                for {
                                  _ <- IO(beforeReentrant.set(true))
                                  _ <- ku.updateWakeup(wfId, Some(Instant.now().plusSeconds(3600)))
                                  _ <- IO.cede
                                  _ <- IO(afterReentrant.set(true))
                                  _ <- done.release
                                } yield ()
            _              <- ku.initialize(wakeUpLogic)
            now             = Instant.now()
            _              <- ku.updateWakeup(id, Some(now.plusMillis(100)))
            _              <- done.await.timeout(2.seconds).attempt
          } yield ()
        }
        .unsafeRunSync()
      assert(beforeReentrant.get(), "wakeup callback did not start")
      assert(afterReentrant.get(), "wakeup callback was cut short after reentrant updateWakeup — self-cancel bug")
    }

    "should throw an exception if trying to initialize wakeupLogic twice" in {
      val wakeUpLogic: WorkflowInstanceId => IO[Unit] = _ => IO.unit

      val test = SleepingKnockerUpper
        .create()
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
