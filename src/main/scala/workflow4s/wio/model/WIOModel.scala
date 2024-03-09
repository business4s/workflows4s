package workflow4s.wio.model

import io.circe.generic.JsonCodec

@JsonCodec
sealed trait WIOModel

object WIOModel {

  case class Sequence(steps: Seq[WIOModel])                                                                            extends WIOModel
  case class Dynamic(name: Option[String], error: Option[Error])                                                       extends WIOModel
  case class RunIO(error: Option[Error], name: Option[String], description: Option[String])                            extends WIOModel
  case class HandleSignal(signalName: String, error: Option[Error], name: Option[String], description: Option[String]) extends WIOModel
  // TODO error name?
  case class HandleError(base: WIOModel, handler: WIOModel, errorName: Option[String])                                 extends WIOModel
  case object Noop                                                                                                     extends WIOModel
  case class Pure(name: Option[String], description: Option[String])                                                   extends WIOModel

  @JsonCodec
  case class Error(name: String)

}
