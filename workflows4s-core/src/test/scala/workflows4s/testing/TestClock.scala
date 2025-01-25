package workflows4s.testing

import com.typesafe.scalalogging.StrictLogging

import java.time.{Clock, Instant, ZoneId, ZoneOffset}
import scala.concurrent.duration.FiniteDuration
import scala.jdk.DurationConverters.ScalaDurationOps

// TODO duplicated in examples
class TestClock extends Clock with StrictLogging {
  var instant_ : Instant                       = Instant.now
  def setInstant(instant: Instant): Unit       = this.instant_ = instant
  def instant: Instant                         = instant_
  def getZone: ZoneId                          = ZoneOffset.UTC
  override def withZone(zoneId: ZoneId): Clock = ???

  def advanceBy(duration: FiniteDuration) = {
    instant_ = this.instant_.plus(duration.toJava)
    logger.debug(s"Advancing time by ${duration} to ${instant_}")
  }
}
