package workflows4s.runtime.wakeup

import org.scalatest.freespec.AnyFreeSpecLike
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.runtime.instanceengine.Effect
import workflows4s.runtime.instanceengine.Effect.*

import java.time.Instant
import scala.concurrent.duration.*

/** Abstract test suite for SleepingKnockerUpper that can be reused with different effect types. Extend this trait and provide the Effect instance for
  * your effect type.
  */
trait SleepingKnockerUpperTestSuite[F[_]] extends AnyFreeSpecLike {

  given effect: Effect[F]

  /** Sleep for the given duration. Implementations should use the effect system's sleep. */
  def sleep(duration: FiniteDuration): F[Unit]

  /** Generate a random workflow instance ID for testing. */
  def randomWfId(): WorkflowInstanceId

  protected def sleepingKnockerUpperTests(): Unit = {

    "should successfully schedule and perform a wakeup" in {
      val id = randomWfId()

      var wokenUp                                    = Vector[WorkflowInstanceId]()
      val wakeUpLogic: WorkflowInstanceId => F[Unit] = id => effect.delay { wokenUp = wokenUp.appended(id) }

      val program = SleepingKnockerUpper
        .create[F]
        .flatMap { ku =>
          for {
            _  <- ku.initialize(wakeUpLogic)
            now = Instant.now()
            _  <- ku.updateWakeup(id, Some(now.plusMillis(100)))
            _  <- sleep(200.millis)
          } yield ()
        }

      effect.runSyncUnsafe(program)
      assert(wokenUp == Vector(id))
    }

    "should cancel a scheduled wakeup when updateWakeup is called with None" in {
      val id                                         = randomWfId()
      var wokenUp                                    = false
      val wakeUpLogic: WorkflowInstanceId => F[Unit] = _ => effect.delay { wokenUp = true }

      val program = SleepingKnockerUpper
        .create[F]
        .flatMap { ku =>
          for {
            _  <- ku.initialize(wakeUpLogic)
            now = Instant.now()
            _  <- ku.updateWakeup(id, Some(now.plusMillis(100)))
            _  <- ku.updateWakeup(id, None)
            _  <- sleep(200.millis)
          } yield ()
        }

      effect.runSyncUnsafe(program)
      assert(!wokenUp)
    }

    "should throw an exception if trying to initialize wakeupLogic twice" in {
      val wakeUpLogic: WorkflowInstanceId => F[Unit] = _ => effect.unit

      val program = SleepingKnockerUpper
        .create[F]
        .flatMap { ku =>
          for {
            _   <- ku.initialize(wakeUpLogic)
            res <- ku.initialize(wakeUpLogic)
          } yield res
        }

      val result = effect.runSyncUnsafe(effect.attempt(program))
      assert(result.isLeft)
    }

    "should cancel all fibers on shutdown" in {
      val id                                         = randomWfId()
      var wokenUp                                    = false
      val wakeUpLogic: WorkflowInstanceId => F[Unit] = _ => effect.delay { wokenUp = true }

      val program = SleepingKnockerUpper
        .create[F]
        .flatMap { ku =>
          for {
            _  <- ku.initialize(wakeUpLogic)
            now = Instant.now()
            _  <- ku.updateWakeup(id, Some(now.plusMillis(200)))
            _  <- sleep(50.millis)
            _  <- ku.shutdown
            _  <- sleep(300.millis)
          } yield ()
        }

      effect.runSyncUnsafe(program)
      assert(!wokenUp)
    }
  }
}
