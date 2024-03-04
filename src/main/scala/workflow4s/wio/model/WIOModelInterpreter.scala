package workflow4s.wio.model

import cats.syntax.all._
import workflow4s.wio.Interpreter.Visitor
import workflow4s.wio.WIO

object WIOModelInterpreter {

  def run(wio: WIO[_, _, _, _]): WIOModel = {
    new ModelVisitor(wio, None, None).run.merge
  }

  private class ModelVisitor[Err, Out, StIn, StOut](wio: WIO[Err, Out, StIn, StOut], name: Option[String], description: Option[String])
      extends Visitor[Err, Out, StIn, StOut](wio) {
    type DirectOut  = WIOModel
    type FlatMapOut = WIOModel

    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[Sig, StIn, StOut, Evt, Out, Err, Resp]): DirectOut =
      WIOModel.HandleSignal(wio.sigDef.reqCt.runtimeClass.getSimpleName, None, name, description) // TODO error
    def onRunIO[Evt](wio: WIO.RunIO[StIn, StOut, Evt, Out, Err]): DirectOut                                        =
      WIOModel.RunIO(None, name, description)
    def onFlatMap[Out1, StOut1](wio: WIO.FlatMap[Err, Out1, Out, StIn, StOut1, StOut]): FlatMapOut = {
      WIOModel.Sequence(Seq(recurse(wio.base), WIOModel.Dynamic()))
    }
    def onMap[Out1](wio: WIO.Map[Err, Out1, Out, StIn, StOut]): DispatchResult                                     =
      recurse(wio.base).asLeft
    def onHandleQuery[Qr, QrSt, Resp](wio: WIO.HandleQuery[Err, Out, StIn, StOut, Qr, QrSt, Resp]): DispatchResult =
      recurse(wio.inner).asLeft
    def onNoop(wio: WIO.Noop): DirectOut                                                                           =
      WIOModel.Noop

    override def onHandleError[ErrIn](wio: WIO.HandleError[Err, Out, StIn, StOut, ErrIn]): DispatchResult = {
      WIOModel.HandleError(recurse(wio.base), WIOModel.Dynamic()).asLeft
    }

    def onNamed(wio: WIO.Named[Err, Out, StIn, StOut]): DispatchResult = new ModelVisitor(wio, wio.name.some, wio.description).run

    def recurse[E1, O1, SIn1, SOut1](wio: WIO[E1, O1, SIn1, SOut1]): WIOModel = {
      new ModelVisitor(wio, name, description).run.merge
    }

  }

}
