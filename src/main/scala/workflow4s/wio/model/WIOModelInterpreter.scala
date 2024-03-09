package workflow4s.wio.model

import cats.syntax.all._
import workflow4s.wio.Interpreter.Visitor
import workflow4s.wio.WIO

object WIOModelInterpreter {

  def run(wio: WIO[_, _, _, _]): WIOModel = {
    new ModelVisitor(wio, Metadata.empty).run
  }

  case class Metadata(name: Option[String], description: Option[String])
  object Metadata {
    val empty = Metadata(None, None)
  }

  private class ModelVisitor[Err, Out, StIn, StOut](wio: WIO[Err, Out, StIn, StOut], m: Metadata) extends Visitor[Err, Out, StIn, StOut](wio) {
    type DirectOut               = WIOModel
    type FlatMapOut              = WIOModel
    override type DispatchResult = WIOModel

    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[Sig, StIn, StOut, Evt, Out, Err, Resp]): DirectOut = {
      val signalName = ModelUtils.getPrettyNameForClass(wio.sigDef.reqCt)
      val errorName  = ModelUtils.getError(wio.errorCt)
      WIOModel.HandleSignal(signalName, errorName, m.name, m.description) // TODO error
    }

    def onRunIO[Evt](wio: WIO.RunIO[StIn, StOut, Evt, Out, Err]): DirectOut = {
      val error = ModelUtils.getError(wio.errorCt)
      WIOModel.RunIO(error, m.name, m.description)
    }

    def onFlatMap[Out1, StOut1, Err1 <: Err](wio: WIO.FlatMap[Err1, Err, Out1, Out, StIn, StOut1, StOut]): FlatMapOut = {
      val error = ModelUtils.getError(wio.errorCt)
      WIOModel.Sequence(Seq(recurse(wio.base, None), WIOModel.Dynamic(m.name, error)))
    }

    override def onAndThen[Out1, StOut1](wio: WIO.AndThen[Err, Out1, Out, StIn, StOut1, StOut]): WIOModel =
      WIOModel.Sequence(Seq(recurse(wio.first, None), recurse(wio.second, None))) // TODO flatten multiple sequences
    def onMap[Out1, StIn1, StOut1](wio: WIO.Map[Err, Out1, Out, StIn1, StIn, StOut1, StOut]): DispatchResult       =
      recurse(wio.base)
    def onHandleQuery[Qr, QrSt, Resp](wio: WIO.HandleQuery[Err, Out, StIn, StOut, Qr, QrSt, Resp]): DispatchResult =
      recurse(wio.inner)
    def onNoop(wio: WIO.Noop): DirectOut                                                                           = WIOModel.Noop
    override def onPure(wio: WIO.Pure[Err, Out, StIn, StOut]): DirectOut                                           = WIOModel.Pure(m.name, m.description)

    override def onHandleError[ErrIn](wio: WIO.HandleError[Err, Out, StIn, StOut, ErrIn]): DispatchResult = {
      val errorName = ModelUtils.getPrettyNameForClass(wio.handledErrorCt)
      val newError  = ModelUtils.getError(wio.newErrorCt)
      WIOModel.HandleError(recurse(wio.base, None), WIOModel.Dynamic(m.name, newError), errorName.some)
    }

    def onNamed(wio: WIO.Named[Err, Out, StIn, StOut]): DispatchResult =
      new ModelVisitor(wio.base, Metadata(wio.name.some, wio.description)).run

    def recurse[E1, O1, SIn1, SOut1](wio: WIO[E1, O1, SIn1, SOut1], meta: Option[Metadata] = Some(m)): WIOModel = {
      new ModelVisitor(wio, meta.getOrElse(Metadata.empty)).run
    }

  }

}
