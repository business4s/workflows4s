package workflow4s.wio.model

import io.circe.derivation.{Configuration, ConfiguredCodec}
import io.circe.{Codec, Decoder, Encoder}
import workflow4s.wio.ErrorMeta

sealed trait WIOModel

object WIOModel {
  given Configuration            = Configuration.default.withDiscriminator("_type")
  given Codec.AsObject[WIOModel] = Codec.AsObject.derivedConfigured

  sealed trait Interruption

  case class Sequence(steps: Seq[WIOModel])                                                        extends WIOModel {
    assert(steps.size >= 2) // TODO could be safer
  }
  case class Dynamic(name: Option[String], error: Option[Error])                                   extends WIOModel
  case class RunIO(error: Option[Error], name: Option[String])                                     extends WIOModel
  case class HandleSignal(signalName: String, error: Option[Error], operationName: Option[String]) extends WIOModel with Interruption
  // TODO error name?
  case class HandleError(base: WIOModel, handler: WIOModel, errorName: Error)                      extends WIOModel
  case object Noop                                                                                 extends WIOModel
  case class Pure(name: Option[String], errorMeta: Option[Error])                                  extends WIOModel
  case class Loop(base: WIOModel, conditionLabel: Option[String])                                  extends WIOModel
  case class Fork(branches: Vector[Branch])                                                        extends WIOModel
  case class Interruptible(base: WIOModel, trigger: HandleSignal, flow: Option[WIOModel])          extends WIOModel

  // as of now we always capture error name. It can change in the future
  case class Error(name: String) derives ConfiguredCodec

  case class Branch(logic: WIOModel, label: Option[String]) derives ConfiguredCodec

}
