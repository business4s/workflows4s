package workflow4s.wio.model

import cats.syntax.all.*
import workflow4s.wio.{Visitor, WCState, WIO, WorkflowContext}
object WIOModelInterpreter {

  def run(wio: WIO[?, ?, ?, ?]): WIOModel = {
    new ModelVisitor(wio, Metadata.empty).run
  }

  case class Metadata(name: Option[String], description: Option[String])

  object Metadata {
    val empty = Metadata(None, None)
  }

  private class ModelVisitor[Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](wio: WIO[In, Err, Out, Ctx], m: Metadata)
      extends Visitor[Ctx, In, Err, Out](wio) {
    override type Result = WIOModel

    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[Ctx, In, Out, Err, Sig, Resp, Evt]): Result           = {
      val signalName = ModelUtils.getPrettyNameForClass(wio.sigDef.reqCt)
      WIOModel.HandleSignal(signalName, wio.errorCt, m.name, m.description) // TODO error
    }
    def onRunIO[Evt](wio: WIO.RunIO[Ctx, In, Err, Out, Evt]): Result                                         = {
      WIOModel.RunIO(wio.errorCt, m.name, m.description)
    }
    def onFlatMap[Out1 <: WCState[Ctx], Err1 <: Err](wio: WIO.FlatMap[Ctx, Err1, Err, Out1, Out, In]): Result   = {
      WIOModel.Sequence(Seq(recurse(wio.base, None), WIOModel.Dynamic(m.name, wio.errorCt)))
    }
    def onMap[In1, Out1 <: WCState[Ctx]](wio: WIO.Map[Ctx, In1, Err, Out1, In, Out]): Result                    = recurse(wio.base)
    def onHandleQuery[Qr, QrState, Resp](wio: WIO.HandleQuery[Ctx, In, Err, Out, Qr, QrState, Resp]): Result = recurse(wio.inner)
    def onNoop(wio: WIO.Noop[Ctx]): Result                                                                   = WIOModel.Noop
    def onNamed(wio: WIO.Named[Ctx, In, Err, Out]): Result                                                   =
      new ModelVisitor(wio.base, Metadata(wio.name.some, wio.description)).run
    def onHandleError[ErrIn, TempOut <: WCState[Ctx]](wio: WIO.HandleError[Ctx, In, Err, Out, ErrIn, TempOut]): Result                         = {
      WIOModel.HandleError(recurse(wio.base, None), WIOModel.Dynamic(m.name, wio.newErrorMeta), wio.handledErrorMeta)
    }
    def onHandleErrorWith[ErrIn](wio: WIO.HandleErrorWith[Ctx, In, ErrIn, Out, Err]): Result                 = {
      WIOModel.HandleError(recurse(wio.base, None), recurse(wio.handleError, None), wio.handledErrorMeta)
    }
    def onAndThen[Out1 <: WCState[Ctx]](wio: WIO.AndThen[Ctx, In, Err, Out1, Out]): Result                      =
      WIOModel.Sequence(Seq(recurse(wio.first, None), recurse(wio.second, None))) // TODO flatten multiple sequences
    def onPure(wio: WIO.Pure[Ctx, In, Err, Out]): Result                                                                                      = WIOModel.Pure(m.name, m.description)
    def onDoWhile[Out1 <: WCState[Ctx]](wio: WIO.DoWhile[Ctx, In, Err, Out1, Out]): Result                                                       =
      WIOModel.Loop(recurse(wio.loop), None)
    def onFork(wio: WIO.Fork[Ctx, In, Err, Out]): Result                                                                                      =
      WIOModel.Fork(wio.branches.map(x => WIOModel.Branch(recurse(x.wio), None)))
    def onEmbedded[InnerCtx <: WorkflowContext, InnerOut <: WCState[InnerCtx], MappingOutput[_] <: WCState[Ctx]](wio: WIO.Embedded[Ctx, In, Err, InnerCtx, InnerOut, MappingOutput]): Result = {
      recurse(wio.inner)
    }

    def recurse[C <: WorkflowContext, I1, E1, O1 <: WCState[C]](wio: WIO[I1, E1, O1, C], meta: Option[Metadata] = Some(m)): WIOModel = {
      new ModelVisitor(wio, meta.getOrElse(Metadata.empty)).run
    }

  }

}
