package workflows4s.runtime.wakeup.filesystem

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.typesafe.scalalogging.StrictLogging
import org.scalatest.concurrent.Eventually.eventually
import org.scalatest.freespec.AnyFreeSpec

import java.nio.file.{Files, Path}
import java.time.{Clock, Instant, ZoneId, ZoneOffset}
import scala.concurrent.duration.*
import scala.jdk.DurationConverters.ScalaDurationOps

class FilesystemKnockerUpperTest extends AnyFreeSpec {

  "basic" in {
    val clock = new TestClock

    withTemporaryDirectory(tempDir => {
      val scheduler               = new FilesystemScheduler(tempDir, clock, 100.millis)
      var wakeups: Vector[String] = Vector()
      val knockerUpperFactory     = FilesystemKnockerUpper.factory[Long](scheduler, _.toString, str => IO(wakeups :+= str))

      val (id1, id2) = (1L, 2L)
      val now        = clock.instant
      val t1         = now.plusSeconds(1)
      val t2         = now.plusSeconds(2)
      knockerUpperFactory
        .use(knockerUpper =>
          knockerUpper(id1).registerWakeup(t1) *>
            knockerUpper(id2).registerWakeup(t2) *> IO {
              assert(wakeups.isEmpty)
              clock.advanceBy(1.1.second)
              eventually {
                assert(wakeups == Vector(id1.toString))
              }
              clock.advanceBy(1.second)
              eventually {
                assert(wakeups == Vector(id1.toString, id2.toString))
              }
            },
        )
        .unsafeRunSync()
    })
  }

  "restart" in {
    val clock = new TestClock

    withTemporaryDirectory(tempDir => {
      val scheduler               = new FilesystemScheduler(tempDir, clock, 100.millis)
      var wakeups: Vector[String] = Vector()
      val knockerUpperFactory     = FilesystemKnockerUpper.factory[Long](scheduler, _.toString, str => IO(wakeups :+= str))

      val (id1, id2) = (1L, 2L)
      val now        = clock.instant
      val t1         = now.plusSeconds(1)
      val t2         = now.plusSeconds(2)
      knockerUpperFactory
        .use(knockerUpper =>
          knockerUpper(id1).registerWakeup(t1) *>
            knockerUpper(id2).registerWakeup(t2) *> IO {
              assert(wakeups.isEmpty)
              clock.advanceBy(1.1.second)
              eventually {
                assert(wakeups == Vector(id1.toString))
              }
            },
        )
        .unsafeRunSync()

      var wakeups2: Vector[String] = Vector()
      // we could reuse the recource, but just to be safe
      val knockerUpperFactory2     = FilesystemKnockerUpper.factory[Long](scheduler, _.toString, str => IO(wakeups2 :+= str))
      knockerUpperFactory2
        .use(_ =>
          IO {
            eventually {
              clock.advanceBy(1.second)
              eventually {
                assert(wakeups2 == Vector(id2.toString))
              }
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

  def withTemporaryDirectory[A](block: Path => A): A = {
    val tempDir = Files.createTempDirectory("tempDir")
    try {
      block(tempDir)
    } finally {
      Files.walk(tempDir).iterator().forEachRemaining { p =>
        p.toFile.delete()
      }
      tempDir.toFile.delete()
    }
  }
}
