package workflows4s.wio.model

import io.circe.{Codec, Decoder, Encoder}

import java.time.{Duration, Instant}

object WIOMeta {

  given Codec[Instant] = Codec.from(Decoder.decodeInstant, Encoder.encodeInstant)

  case class Dynamic(error: Option[Error]) derives Codec
  case class Pure(name: Option[String], error: Option[WIOMeta.Error]) derives Codec
  // TODO, probably need better model to express started or not timer
  case class Timer(duration: Option[Duration], releaseAt: Option[Instant], name: Option[String]) derives Codec
  case class RunIO(name: Option[String], error: Option[WIOMeta.Error]) derives Codec
  case class HandleSignal(signalName: String, operationName: Option[String], error: Option[WIOMeta.Error]) derives Codec
  case class Loop(
      conditionName: Option[String],
      exitBranchName: Option[String],
      restartBranchName: Option[String],
  ) derives Codec
  case class Fork(name: Option[String], branches: Vector[Branch]) derives Codec
  case class HandleError(newErrorMeta: Option[WIOMeta.Error], handledErrorMeta: Option[WIOMeta.Error]) derives Codec

  case class Error(name: String) derives Codec
  case class Branch(name: Option[String]) derives Codec
}
