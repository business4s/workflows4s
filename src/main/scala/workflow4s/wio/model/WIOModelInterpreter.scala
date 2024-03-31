package workflow4s.wio.model

import cats.syntax.all.*
import workflow4s.wio.{VisitorModule, WorkflowContext}

trait WIOModelInterpreterModule extends VisitorModule {
  import c.WIO

  object WIOModelInterpreter {

    def run(wio: WIO[_, _, _, _]): WIOModel = {
      new ModelVisitor(wio, Metadata.empty).run
    }

    case class Metadata(name: Option[String], description: Option[String])

    object Metadata {
      val empty = Metadata(None, None)
    }

    private class ModelVisitor[Err, Out, StIn, StOut](wio: WIO[Err, Out, StIn, StOut], m: Metadata) extends Visitor[Err, Out, StIn, StOut](wio) {
      override type DispatchResult = WIOModel

      override def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[Sig, StIn, StOut, Evt, Out, Err, Resp]): DispatchResult = {
        val signalName = ModelUtils.getPrettyNameForClass(wio.sigDef.reqCt)
        WIOModel.HandleSignal(signalName, wio.errorCt, m.name, m.description) // TODO error
      }

      override def onRunIO[Evt](wio: WIO.RunIO[StIn, StOut, Evt, Out, Err]): DispatchResult = {
        WIOModel.RunIO(wio.errorCt, m.name, m.description)
      }

      override def onFlatMap[Out1, StOut1, Err1 <: Err](wio: WIO.FlatMap[Err1, Err, Out1, Out, StIn, StOut1, StOut]): DispatchResult = {
        WIOModel.Sequence(Seq(recurse(wio.base, None), WIOModel.Dynamic(m.name, wio.errorCt)))
      }

      override def onAndThen[Out1, StOut1](wio: WIO.AndThen[Err, Out1, Out, StIn, StOut1, StOut]): DispatchResult =
        WIOModel.Sequence(Seq(recurse(wio.first, None), recurse(wio.second, None))) // TODO flatten multiple sequences

      override def onMap[Out1, StIn1, StOut1](wio: WIO.Map[Err, Out1, Out, StIn1, StIn, StOut1, StOut]): DispatchResult =
        recurse(wio.base)

      override def onHandleQuery[Qr, QrSt, Resp](wio: WIO.HandleQuery[Err, Out, StIn, StOut, Qr, QrSt, Resp]): DispatchResult =
        recurse(wio.inner)

      override def onNoop(wio: WIO.Noop): WIOModel = WIOModel.Noop

      override def onPure(wio: WIO.Pure[Err, Out, StIn, StOut]): WIOModel = WIOModel.Pure(m.name, m.description)

      override def onHandleError[ErrIn](wio: WIO.HandleError[Err, Out, StIn, StOut, ErrIn]): DispatchResult = {
        WIOModel.HandleError(recurse(wio.base, None), WIOModel.Dynamic(m.name, wio.newErrorCt), wio.handledErrorCt)
      }

      override def onHandleErrorWith[ErrIn, HandlerStateIn >: StIn, BaseOut >: Out](
          wio: WIO.HandleErrorWith[Err, BaseOut, StIn, StOut, ErrIn, HandlerStateIn, Out],
      ): DispatchResult = {
        WIOModel.HandleError(recurse(wio.base, None), recurse(wio.handleError, None), wio.handledErrorMeta)
      }

      override def onNamed(wio: WIO.Named[Err, Out, StIn, StOut]): DispatchResult =
        new ModelVisitor(wio.base, Metadata(wio.name.some, wio.description)).run

      override def onDoWhile[StOut1](wio: WIO.DoWhile[Err, Out, StIn, StOut1, StOut]): WIOModel =
        WIOModel.Loop(recurse(wio.loop), None)

      override def onFork(wio: WIO.Fork[Err, Out, StIn, StOut]): WIOModel =
        WIOModel.Fork(wio.branches.map(x => WIOModel.Branch(recurse(x.wio), None)))

      def recurse[E1, O1, SIn1, SOut1](wio: WIO[E1, O1, SIn1, SOut1], meta: Option[Metadata] = Some(m)): WIOModel = {
        new ModelVisitor(wio, meta.getOrElse(Metadata.empty)).run
      }

    }

  }
}
