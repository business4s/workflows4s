package workflow4s.wio.model

import io.circe.derivation.{Configuration, ConfiguredCodec}
import io.circe.{Codec, Decoder, Encoder}
import workflow4s.wio.ErrorMeta

import java.time.Duration

sealed trait WIOModel

object WIOModel {
  given Configuration            = Configuration.default.withDiscriminator("_type")
  given Codec.AsObject[WIOModel] = Codec.AsObject.derivedConfigured

  given Codec.AsObject[Interruption] = Codec.AsObject.derivedConfigured
  sealed trait Interruption

  case class Sequence(steps: Seq[WIOModel])                                                        extends WIOModel {
    assert(steps.size >= 2) // TODO could be safer
  }
  case class Dynamic(name: Option[String], error: Option[Error])                                   extends WIOModel
  case class RunIO(error: Option[Error], name: Option[String])                                     extends WIOModel
  case class HandleSignal(signalName: String, error: Option[Error], operationName: Option[String]) extends WIOModel with Interruption
  // TODO error name?
  case class HandleError(base: WIOModel, handler: WIOModel, error: Option[Error])                  extends WIOModel
  case object Noop                                                                                 extends WIOModel
  case class Pure(name: Option[String], error: Option[Error])                                      extends WIOModel
  case class Loop(
      base: WIOModel,
      conditionName: Option[String],
      exitBranchName: Option[String],
      restartBranchName: Option[String],
      onRestart: Option[WIOModel],
  ) extends WIOModel
  case class Fork(branches: Vector[Branch], name: Option[String])                                  extends WIOModel
  case class Interruptible(base: WIOModel, trigger: Interruption, flow: Option[WIOModel])          extends WIOModel
  case class Timer(duration: Option[Duration], name: Option[String])                               extends WIOModel with Interruption

  // as of now we always capture error name. It can change in the future
  case class Error(name: String) derives ConfiguredCodec

  case class Branch(logic: WIOModel, label: Option[String]) derives ConfiguredCodec

}
