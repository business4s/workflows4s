package workflows4s.wio.model


import io.circe.Codec

import java.time.Duration

object WIOMeta {
  case class Dynamic(error: Option[Error]) derives Codec
  case class Pure(name: Option[String], error: Option[WIOMeta.Error]) derives Codec
  case class Timer(duration: Option[Duration], name: Option[String]) derives Codec
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
