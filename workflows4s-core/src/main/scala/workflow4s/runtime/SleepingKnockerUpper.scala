package workflow4s.runtime

import cats.effect.IO
import workflow4s.wio.KnockerUpper

import java.time.{Duration, Instant}
import scala.jdk.DurationConverters.JavaDurationOps

/** Simple implementation for KnockerUpper that relies on IO.sleep It doesn't offer cancellation of registered wakeups
  */
class SleepingKnockerUpper(wakeupLogic: IO[Unit]) extends KnockerUpper {

  // TODO logging
  override def registerWakeup(at: Instant): IO[Unit] =
    (for {
      now <- IO(Instant.now())
      _   <- IO.sleep(Duration.between(now, at).toScala)
      _   <- wakeupLogic
    } yield ()).start.void
}
