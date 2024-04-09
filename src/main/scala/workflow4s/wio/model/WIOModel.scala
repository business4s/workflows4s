package workflow4s.wio.model

import io.circe.{Decoder, Encoder}
import workflow4s.wio.ErrorMeta

sealed trait WIOModel derives Encoder.AsObject, Decoder

object WIOModel {

  sealed trait Interruption

  case class Sequence(steps: Seq[WIOModel])                                                   extends WIOModel{
    assert(steps.size >= 2) // TODO could be safer
  }
  case class Dynamic(name: Option[String], error: ErrorMeta[_])                               extends WIOModel
  case class RunIO(error: ErrorMeta[_], name: Option[String], description: Option[String])    extends WIOModel
  case class HandleSignal(signalName: String, error: ErrorMeta[_], name: Option[String], description: Option[String])
      extends WIOModel
      with Interruption
  // TODO error name?
  case class HandleError(base: WIOModel, handler: WIOModel, errorName: ErrorMeta[_])          extends WIOModel
  case object Noop                                                                            extends WIOModel
  case class Pure(name: Option[String], description: Option[String], errorMeta: ErrorMeta[_]) extends WIOModel
  case class Loop(base: WIOModel, conditionLabel: Option[String])                             extends WIOModel
  case class Fork(branches: Vector[Branch])                                                   extends WIOModel
  case class Interruptible(base: WIOModel, trigger: HandleSignal, flow: Option[WIOModel])     extends WIOModel

  case class Error(name: String) derives Encoder.AsObject, Decoder

  case class Branch(logic: WIOModel, label: Option[String]) derives Encoder.AsObject, Decoder

}
