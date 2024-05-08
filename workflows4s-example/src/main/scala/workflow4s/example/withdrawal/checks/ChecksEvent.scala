package workflow4s.example.withdrawal.checks

import io.circe.Codec
import io.circe.syntax.EncoderOps
import org.apache.pekko.serialization.Serializer

import java.time.Instant

sealed trait ChecksEvent derives Codec.AsObject
object ChecksEvent {
  case class ChecksRun(results: Map[CheckKey, CheckResult]) extends ChecksEvent
  case class ReviewDecisionTaken(decision: ReviewDecision)  extends ChecksEvent
  case class AwaitingRefresh(started: Instant)              extends ChecksEvent
  case class RefreshReleased(released: Instant)             extends ChecksEvent
  case class AwaitingTimeout(started: Instant)              extends ChecksEvent
  case class ExecutionTimedOut(releasedAt: Instant)         extends ChecksEvent

  class PekkoSerializer extends Serializer {
    override def includeManifest: Boolean = true
    override def identifier = 12345677

    override def toBinary(obj: AnyRef): Array[Byte] = {
      obj match {
        case e: ChecksEvent => e.asJson.noSpaces.getBytes
        case other => ???
      }
    }
    def fromBinary(bytes: Array[Byte], clazz: Option[Class[_]]): AnyRef = {
      io.circe.parser.decode(String(bytes)).toTry.get
    }
  }
}
