package workflows4s.runtime.wakeup.filesystem

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.implicits.catsSyntaxOptionId
import com.typesafe.scalalogging.StrictLogging
import org.scalatest.concurrent.Eventually.eventually
import org.scalatest.freespec.AnyFreeSpec
import workflows4s.runtime.wakeup.filesystem.FilesystemKnockerUpper.StringCodec

import java.nio.file.{Files, Path}
import java.time.{Clock, Instant, ZoneId, ZoneOffset}
import scala.concurrent.duration.*
import scala.jdk.DurationConverters.ScalaDurationOps

class FilesystemKnockerUpperTest extends AnyFreeSpec {

  "basic" in {
    val clock = new TestClock

    withTemporaryDirectory(tempDir => {
      val scheduler             = new PollingFsScheduler(tempDir, clock, 100.millis)
      var wakeups: Vector[Long] = Vector()
      val knockerUpper          = new FilesystemKnockerUpper[Long](scheduler)

      val (id1, id2) = (1L, 2L)
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
      val scheduler             = new PollingFsScheduler(tempDir, clock, 100.millis)
      var wakeups: Vector[Long] = Vector()
      val knockerUpper          = new FilesystemKnockerUpper[Long](scheduler)

      val id1 = 1L
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

  // TODO move somewhere to reuse between modules
  class TestClock extends Clock with StrictLogging {
    var instant_ : Instant = Instant.now

    def setInstant(instant: Instant): Unit       = this.instant_ = instant
    def instant: Instant                         = instant_
    def getZone: ZoneId                          = ZoneOffset.UTC
    override def withZone(zoneId: ZoneId): Clock = ???

    def advanceBy(duration: FiniteDuration): Unit = {
      instant_ = this.instant_.plus(duration.toJava)
      logger.debug(s"Advancing time by ${duration} to ${instant_}")
    }
  }

  def withTemporaryDirectory[A](block: fs2.io.file.Path => A): A = {
    val tempDir = Files.createTempDirectory("tempDir")
    try {
      block(fs2.io.file.Path.fromNioPath((tempDir)))
    } finally {
      Files.walk(tempDir).iterator().forEachRemaining { p =>
        p.toFile.delete()
      }
      tempDir.toFile.delete()
    }
  }

  given StringCodec[Long] with {
    override def encode(value: Long): String = value.toString

    override def decode(value: String): Long = value.toLong
  }
}
