package workflow4s.wio.internal

import workflow4s.wio.internal.WorkflowConversionEvaluator.WorkflowEmbedding
import workflow4s.wio.{VisitorModule, WorkflowContext}

abstract class WorkflowConversionEvaluator[SrcCtx <: WorkflowContext] extends VisitorModule[SrcCtx] {
  import c.WIO
  val destCtx: WorkflowContext // shoparameter to `conver` but compiler fails to deal with it then
  val embedding: WorkflowEmbedding[c.type, destCtx.type]

  def convert[In, Err, Out](wio: WIO[In, Err, Out]): destCtx.WIO[In, Err, Out] = {
    new ConversionVisitor(wio).run.asInstanceOf[destCtx.WIO[In, Err, Out]] // TODO, compile forgots its the same type
  }

  final private class ConversionVisitor[In, Err, Out](wio: WIO[In, Err, Out]) extends Visitor[In, Err, Out](wio) {
    override type Result = destCtx.WIO[In, Err, Out]

    def recurse[I1, E1, O1](w: WIO[I1, E1, O1]): destCtx.WIO[I1, E1, O1] =
      new ConversionVisitor(w).run.asInstanceOf // TODO, compile forgots its the same type

    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[In, Out, Err, Sig, Resp, Evt]): Result =
      destCtx.WIO.HandleSignal(
        wio.sigDef,
        wio.sigHandler,
        wio.evtHandler.xmapEvt(embedding.unconvertEvent, embedding.convertEvent),
        wio.errorCt,
      )
    def onRunIO[Evt](wio: WIO.RunIO[In, Err, Out, Evt]): Result                               =
      destCtx.WIO.RunIO(
        wio.buildIO,
        wio.evtHandler.xmapEvt(embedding.unconvertEvent, embedding.convertEvent),
        wio.errorCt,
      )
    def onFlatMap[Out1, Err1 <: Err](wio: WIO.FlatMap[Err1, Err, Out1, Out, In]): Result                 =
      destCtx.WIO.FlatMap(recurse(wio.base), x => recurse(wio.getNext(x)), wio.errorCt)
    def onMap[In1, Out1](wio: WIO.Map[In1, Err, Out1, In, Out]): Result                                  =
      destCtx.WIO.Map(recurse(wio.base), wio.contramapInput, wio.mapValue)
    def onHandleQuery[Qr, QrState, Resp](wio: WIO.HandleQuery[In, Err, Out, Qr, QrState, Resp]): Result  =
      destCtx.WIO.HandleQuery(wio.queryHandler, recurse(wio.inner))
    def onNoop(wio: WIO.Noop): Result                                                                    =
      destCtx.WIO.Noop()
    def onNamed(wio: WIO.Named[In, Err, Out]): Result                                                    =
      destCtx.WIO.Named(recurse(wio.base), wio.name, wio.description, wio.errorMeta)
    def onHandleError[ErrIn](wio: WIO.HandleError[In, Err, Out, ErrIn]): Result                          =
      destCtx.WIO.HandleError(recurse(wio.base), wio.handleError.andThen(recurse), wio.handledErrorMeta, wio.newErrorMeta)
    def onHandleErrorWith[ErrIn](wio: WIO.HandleErrorWith[In, ErrIn, Out, Err]): Result                  =
      destCtx.WIO.HandleErrorWith(recurse(wio.base), recurse(wio.handleError), wio.handledErrorMeta, wio.newErrorCt)
    def onAndThen[Out1](wio: WIO.AndThen[In, Err, Out1, Out]): Result                                    =
      destCtx.WIO.AndThen(recurse(wio.first), recurse(wio.second))
    def onPure(wio: WIO.Pure[In, Err, Out]): Result                                                      =
      destCtx.WIO.Pure(wio.value, wio.errorMeta)
    def onDoWhile[Out1](wio: WIO.DoWhile[In, Err, Out1, Out]): Result                                    =
      destCtx.WIO.DoWhile(recurse(wio.loop), wio.stopCondition, recurse(wio.current))
    def onFork(wio: WIO.Fork[In, Err, Out]): Result                                                      =
      destCtx.WIO.Fork(wio.branches.map(b => destCtx.WIO.Branch(b.condition, recurse(b.wio))))

  }

}

object WorkflowConversionEvaluator {

  trait WorkflowEmbedding[Inner <: WorkflowContext, Outer <: WorkflowContext] {
    def convertEvent(e: Inner#Event): Outer#Event
    def unconvertEvent(e: Outer#Event): Option[Inner#Event]
  }

}
