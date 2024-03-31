package workflow4s.wio.internal

import workflow4s.wio.{VisitorModule, WorkflowContext}

trait WorkflowConversionEvaluatorModule extends VisitorModule {
  import c.WIO
  val destCtx: WorkflowContext // shoparameter to `conver` but compiler fails to deal with it then

  def convert[Err, Out, StIn, StOut](wio: WIO[Err, Out, StIn, StOut]): destCtx.WIO[Err, Out, StIn, StOut] = {
    new ConversionVisitor(wio).run.asInstanceOf[destCtx.WIO[Err, Out, StIn, StOut]] // TODO, compile forgots its the same type
  }

  final class ConversionVisitor[Err, Out, StIn, StOut](wio: WIO[Err, Out, StIn, StOut])
      extends Visitor[Err, Out, StIn, StOut](wio) {
    override type DispatchResult = destCtx.WIO[Err, Out, StIn, StOut]

    def recurse[E1, O1, StIn1, StOut1](w: WIO[E1, O1, StIn1, StOut1]): destCtx.WIO[E1, O1, StIn1, StOut1] =
      new ConversionVisitor(w).run.asInstanceOf // TODO, compile forgots its the same type

    override def onSignal[Sig, Evt, Resp](wio: c.WIO.HandleSignal[Sig, StIn, StOut, Evt, Out, Err, Resp]): destCtx.WIO[Err, Out, StIn, StOut] =
      destCtx.WIO.HandleSignal(wio.sigDef, wio.sigHandler, wio.evtHandler, wio.getResp, wio.errorCt)

    override def onRunIO[Evt](wio: c.WIO.RunIO[StIn, StOut, Evt, Out, Err]): destCtx.WIO[Err, Out, StIn, StOut] =
      destCtx.WIO.RunIO(wio.buildIO, wio.evtHandler, wio.errorCt)

    override def onFlatMap[Out1, StOut1, Err1 <: Err](wio: c.WIO.FlatMap[Err1, Err, Out1, Out, StIn, StOut1, StOut]): destCtx.WIO[Err, Out, StIn, StOut] =
      destCtx.WIO.FlatMap(recurse(wio.base), x => recurse(wio.getNext(x)), wio.errorCt)

    override def onMap[Out1, StIn1, StOut1](wio: c.WIO.Map[Err, Out1, Out, StIn1, StIn, StOut1, StOut]): destCtx.WIO[Err, Out, StIn, StOut] =
      destCtx.WIO.Map(recurse(wio.base), wio.contramapState, wio.mapValue)

    override def onHandleQuery[Qr, QrSt, Resp](wio: c.WIO.HandleQuery[Err, Out, StIn, StOut, Qr, QrSt, Resp]): destCtx.WIO[Err, Out, StIn, StOut] = ???

    override def onNoop(wio: c.WIO.Noop): destCtx.WIO[Err, Out, StIn, StOut] = destCtx.WIO.Noop()

    override def onNamed(wio: c.WIO.Named[Err, Out, StIn, StOut]): destCtx.WIO[Err, Out, StIn, StOut] =
      destCtx.WIO.Named(recurse(wio.base), wio.name, wio.description, wio.errorMeta)

    override def onHandleError[ErrIn](wio: c.WIO.HandleError[Err, Out, StIn, StOut, ErrIn]): destCtx.WIO[Err, Out, StIn, StOut] =
      destCtx.WIO.HandleError(recurse(wio.base), wio.handleError.andThen(recurse), wio.handledErrorCt, wio.newErrorCt)

    override def onHandleErrorWith[ErrIn, HandlerStateIn >: StIn, BaseOut >: Out](wio: c.WIO.HandleErrorWith[Err, BaseOut, StIn, StOut, ErrIn, HandlerStateIn, Out]): destCtx.WIO[Err, Out, StIn, StOut] =
      destCtx.WIO.HandleErrorWith(recurse(wio.base), recurse(wio.handleError), wio.handledErrorMeta, wio.newErrorCt)

    override def onAndThen[Out1, StOut1](wio: c.WIO.AndThen[Err, Out1, Out, StIn, StOut1, StOut]): destCtx.WIO[Err, Out, StIn, StOut] =
      destCtx.WIO.AndThen(recurse(wio.first), recurse(wio.second))

    override def onPure(wio: c.WIO.Pure[Err, Out, StIn, StOut]): destCtx.WIO[Err, Out, StIn, StOut] =
      destCtx.WIO.Pure(wio.value, wio.errorCt)
      
    override def onDoWhile[StOut1](wio: c.WIO.DoWhile[Err, Out, StIn, StOut1, StOut]): destCtx.WIO[Err, Out, StIn, StOut] =
      destCtx.WIO.DoWhile(recurse(wio.loop), wio.stopCondition, recurse(wio.current))

    override def onFork(wio: c.WIO.Fork[Err, Out, StIn, StOut]): destCtx.WIO[Err, Out, StIn, StOut] =
      destCtx.WIO.Fork(wio.branches.map(b => destCtx.WIO.Branch(b.condition, recurse(b.wio))))
  }

}
