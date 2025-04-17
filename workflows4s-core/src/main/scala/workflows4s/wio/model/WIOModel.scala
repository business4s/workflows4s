package workflows4s.wio.model

import io.circe.derivation.{Configuration, ConfiguredCodec}
import io.circe.{Codec, Decoder, Encoder}

sealed trait WIOModel {
  def toEmptyProgress: WIOExecutionProgress[Nothing] = WIOExecutionProgress.fromModel(this)
}

object WIOModel {
  given Configuration            = Configuration.default.withDiscriminator("_type")
  given Codec.AsObject[WIOModel] = Codec.AsObject.derivedConfigured

  given Codec.AsObject[Interruption] = Codec.AsObject.derivedConfigured
  sealed trait Interruption extends WIOModel

  case class Sequence(steps: Seq[WIOModel])                                                  extends WIOModel {
    assert(steps.size >= 2) // TODO could be safer
  }
  case class Dynamic(meta: WIOMeta.Dynamic)                                                  extends WIOModel
  case class RunIO(meta: WIOMeta.RunIO)                                                      extends WIOModel
  case class HandleSignal(meta: WIOMeta.HandleSignal)                                        extends WIOModel with Interruption
  case class HandleError(base: WIOModel, handler: WIOModel, meta: WIOMeta.HandleError)       extends WIOModel
  case object End                                                                            extends WIOModel
  case class Pure(meta: WIOMeta.Pure)                                                        extends WIOModel
  case class Loop(
      base: WIOModel,
      onRestart: Option[WIOModel],
      meta: WIOMeta.Loop,
  ) extends WIOModel
  case class Fork(branches: Vector[WIOModel], meta: WIOMeta.Fork)                            extends WIOModel
  // handle flow is optional because handling might end on single step(the trigger)
  case class Interruptible(base: WIOModel, trigger: Interruption, handler: Option[WIOModel]) extends WIOModel
  case class Timer(meta: WIOMeta.Timer)                                                      extends WIOModel with Interruption
  case class Parallel(elements: Seq[WIOModel])                                               extends WIOModel
  case class Checkpoint(base: Option[WIOModel])                                              extends WIOModel

}
