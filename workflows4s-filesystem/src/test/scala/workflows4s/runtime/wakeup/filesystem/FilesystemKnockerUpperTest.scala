package workflows4s.runtime.wakeup.filesystem

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.implicits.catsSyntaxOptionId
import org.scalatest.concurrent.Eventually.eventually
import org.scalatest.freespec.AnyFreeSpec
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.testing.{TestClock, TestUtils}

import java.nio.file.Files
import scala.concurrent.duration.*

class FilesystemKnockerUpperTest extends AnyFreeSpec {

  "basic" in {
    val clock = new TestClock

    withTemporaryDirectory(tempDir => {
      val scheduler                           = new PollingFsScheduler(tempDir, clock, 100.millis)
      var wakeups: Vector[WorkflowInstanceId] = Vector()
      val knockerUpper                        = new FilesystemKnockerUpper(scheduler)

      val (id1, id2) = (TestUtils.randomWfId(), TestUtils.randomWfId())
      val now        = clock.instant
      val t1         = now.plusSeconds(1)
      val t2         = now.plusSeconds(2)
      knockerUpper
        .initialize(id => IO(wakeups :+= id))
        .use(_ =>
          knockerUpper.updateWakeup(id1, t1.some) *>
            knockerUpper.updateWakeup(id2, t2.some) *> IO {
              assert(wakeups.isEmpty)
              clock.advanceBy(1.1.second)
              eventually {
                assert(wakeups == Vector(id1))
              }
              clock.advanceBy(1.second)
              eventually {
                assert(wakeups == Vector(id1, id2))
              }
            },
        )
        .unsafeRunSync()
    })
  }

  "restart" in {
    val clock = new TestClock

    withTemporaryDirectory(tempDir => {
      val scheduler                           = new PollingFsScheduler(tempDir, clock, 100.millis)
      var wakeups: Vector[WorkflowInstanceId] = Vector()
      val knockerUpper                        = new FilesystemKnockerUpper(scheduler)

      val id1 = TestUtils.randomWfId()
      val now = clock.instant
      val t1  = now.plusSeconds(1)
      knockerUpper.updateWakeup(id1, t1.some).unsafeRunSync()
      knockerUpper
        .initialize(id => IO(wakeups :+= id))
        .use(_ =>
          IO {
            assert(wakeups.isEmpty)
            clock.advanceBy(1.1.second)
            eventually {
              assert(wakeups == Vector(id1))
            }
          },
        )
        .unsafeRunSync()
    })
  }

  def withTemporaryDirectory[A](block: fs2.io.file.Path => A): A = {
    val tempDir = Files.createTempDirectory("tempDir")
    try {
      block(fs2.io.file.Path.fromNioPath((tempDir)))
    } finally {
      Files.walk(tempDir).iterator().forEachRemaining { p =>
        p.toFile.delete()
        ()
      }
      tempDir.toFile.delete()
      ()
    }
  }

}
