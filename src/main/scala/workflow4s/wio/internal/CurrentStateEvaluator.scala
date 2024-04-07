package workflow4s.wio.internal

import cats.effect.IO
import cats.syntax.all._
import workflow4s.wio.Interpreter.SignalResponse
import workflow4s.wio._

object CurrentStateEvaluator {
  def getCurrentStateDescription(
      wio: WIO[?, ?, ?, ?],
  ): String = {
    val visitor = new DescriptionVisitor(wio)
    visitor.run
  }

  private class DescriptionVisitor[Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](wio: WIO[In, Err, Out, Ctx])
      extends Visitor[Ctx, In, Err, Out](wio) {
    override type Result = String

    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[Ctx, In, Out, Err, Sig, Resp, Evt]): Result                                            =
      s"Expects signal ${wio.sigHandler.ct.runtimeClass.getSimpleName}"
    def onRunIO[Evt](wio: WIO.RunIO[Ctx, In, Err, Out, Evt]): Result                                                                          = "Awaits IO execution"
    def onFlatMap[Out1 <: State, Err1 <: Err](wio: WIO.FlatMap[Ctx, Err1, Err, Out1, Out, In]): Result                                        = recurse(wio.base)
    def onMap[In1, Out1 <: State](wio: WIO.Map[Ctx, In1, Err, Out1, In, Out]): Result                                                         = recurse(wio.base)
    def onNoop(wio: WIO.Noop[Ctx]): Result                                                                                                    = "Noop"
    def onNamed(wio: WIO.Named[Ctx, In, Err, Out]): Result                                                                                    = recurse(wio.base)
    def onHandleError[ErrIn, TempOut <: WCState[Ctx]](wio: WIO.HandleError[Ctx, In, Err, Out, ErrIn, TempOut]): Result                                                          = s"(Handle error or ${recurse(wio.base)})"
    def onHandleErrorWith[ErrIn](wio: WIO.HandleErrorWith[Ctx, In, ErrIn, Out, Err]): Result                                                  =
      s"(${recurse(wio.base)}, on error: ${recurse(wio.handleError)}"
    def onAndThen[Out1 <: State](wio: WIO.AndThen[Ctx, In, Err, Out1, Out]): Result                                                           = recurse(wio.first)
    def onPure(wio: WIO.Pure[Ctx, In, Err, Out]): Result                                                                                      = "pure"
    def onDoWhile[Out1 <: State](wio: WIO.DoWhile[Ctx, In, Err, Out1, Out]): Result                                                           = s"do-while; current = ${recurse(wio.current)}"
    def onFork(wio: WIO.Fork[Ctx, In, Err, Out]): Result                                                                                      = "fork"
    def onEmbedded[InnerCtx <: WorkflowContext, InnerOut <: WCState[InnerCtx], MappingOutput[_] <: WCState[Ctx]](wio: WIO.Embedded[Ctx, In, Err, InnerCtx, InnerOut, MappingOutput]): Result =
      recurse(wio.inner)

    private def recurse[C <: WorkflowContext, I1, E1, O1 <: WCState[C]](wio: WIO[I1, E1, O1, C]): String = new DescriptionVisitor(wio).run

  }

}
