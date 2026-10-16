package workflows4s.wio.model

import io.circe.derivation.{Configuration, ConfiguredCodec}
import io.circe.{Codec, Decoder, Encoder}

sealed trait WIOModel {
  def toEmptyProgress: WIOExecutionProgress[Nothing] = WIOExecutionProgress.fromModel(this)

  /** The errors that this step can raise and that are not handled inside it. */
  def unhandledErrors: Seq[WIOMeta.Error] = {
    val errors: Seq[WIOMeta.Error] = this match {
      case WIOModel.Sequence(steps)                       => steps.flatMap(_.unhandledErrors)
      case WIOModel.Dynamic(meta)                         => meta.error.toSeq
      case WIOModel.RunIO(meta)                           => meta.error.toSeq
      case WIOModel.HandleSignal(meta)                    => meta.error.toSeq
      case WIOModel.HandleError(_, handler, _)            => handler.unhandledErrors
      case WIOModel.End                                   => Seq.empty
      case WIOModel.Pure(meta)                            => meta.error.toSeq
      case WIOModel.Loop(base, onRestart, _)              => base.unhandledErrors ++ onRestart.toSeq.flatMap(_.unhandledErrors)
      case WIOModel.Fork(branches, _)                     => branches.flatMap(_.unhandledErrors)
      case WIOModel.Interruptible(base, trigger, handler) =>
        base.unhandledErrors ++ trigger.unhandledErrors ++ handler.toSeq.flatMap(_.unhandledErrors)
      case WIOModel.Timer(_)                              => Seq.empty
      case WIOModel.Parallel(elements)                    => elements.flatMap(_.unhandledErrors)
      case WIOModel.Checkpoint(base)                      => base.unhandledErrors
      case WIOModel.Recovery()                            => Seq.empty
      case WIOModel.Retried(base)                         => base.unhandledErrors
      case WIOModel.ForEach(forEach, _)                   => forEach.unhandledErrors
    }
    errors.distinct
  }
}

object WIOModel {
  given Configuration            = Configuration.default.withDiscriminator("_type")
  given Codec.AsObject[WIOModel] = Codec.AsObject.derivedConfigured

  given Codec.AsObject[Interruption] = Codec.AsObject.derivedConfigured
  sealed trait Interruption extends WIOModel

  case class Sequence(steps: Seq[WIOModel])                                                  extends WIOModel {
    assert(steps.size >= 2, "Sequence should contain at least two steps")
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
  case class Checkpoint(base: WIOModel)                                                      extends WIOModel
  case class Recovery()                                                                      extends WIOModel
  case class Retried(base: WIOModel)                                                         extends WIOModel
  case class ForEach(forEach: WIOModel, meta: WIOMeta.ForEach)                               extends WIOModel

}
