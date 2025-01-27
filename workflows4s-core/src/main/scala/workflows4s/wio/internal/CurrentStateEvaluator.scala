package workflows4s.wio.internal

import scala.annotation.nowarn
import cats.syntax.all.*
import workflows4s.wio.*

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

    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[Ctx, In, Out, Err, Sig, Resp, Evt]): Result     =
      withName(s"Expects signal ${wio.sigHandler.ct.runtimeClass.getSimpleName}")
    def onRunIO[Evt](wio: WIO.RunIO[Ctx, In, Err, Out, Evt]): Result                                   = withName("Awaits IO execution")
    def onFlatMap[Out1 <: State, Err1 <: Err](wio: WIO.FlatMap[Ctx, Err1, Err, Out1, Out, In]): Result = recurseUnique(wio.base)

    def onTransform[In1, Out1 <: State, Err1](wio: WIO.Transform[Ctx, In1, Err1, Out1, In, Out, Err]): Result          = recurseUnique(wio.base)
    def onNoop(wio: WIO.End[Ctx]): Result                                                                              = "Noop"
    def onNamed(wio: WIO.Named[Ctx, In, Err, Out]): Result                                                             = withName(recurseUnique(wio.base))
    def onHandleError[ErrIn, TempOut <: WCState[Ctx]](wio: WIO.HandleError[Ctx, In, Err, Out, ErrIn, TempOut]): Result =
      s"(Handle error or ${recurseUnique(wio.base)})"
    def onHandleErrorWith[ErrIn](wio: WIO.HandleErrorWith[Ctx, In, ErrIn, Out, Err]): Result                           =
      s"(${recurse(wio.base)}, on error: ${recurse(wio.handleError)}"
    def onAndThen[Out1 <: State](wio: WIO.AndThen[Ctx, In, Err, Out1, Out]): Result                                    = recurseUnique(wio.first)
    def onPure(wio: WIO.Pure[Ctx, In, Err, Out]): Result                                                               = withName("pure")
    def onLoop[Out1 <: State](wio: WIO.Loop[Ctx, In, Err, Out1, Out]): Result                                          = s"do-while; current = ${recurseUnique(wio.current)}"
    def onFork(wio: WIO.Fork[Ctx, In, Err, Out]): Result                                                               = "fork"
    def onEmbedded[InnerCtx <: WorkflowContext, InnerOut <: WCState[InnerCtx], MappingOutput[_] <: WCState[Ctx]](
        wio: WIO.Embedded[Ctx, In, Err, InnerCtx, InnerOut, MappingOutput],
    ): Result =
      recurseUnique(wio.inner)
    def onHandleInterruption(wio: WIO.HandleInterruption[Ctx, In, Err, Out]): Result                                   =
      s"${recurse(wio.base)}, interruptible with: ${recurse(wio.interruption)} )"

    def onTimer(wio: WIO.Timer[Ctx, In, Err, Out]): Result               = withName("timer")
    def onAwaitingTime(wio: WIO.AwaitingTime[Ctx, In, Err, Out]): Result = s"awaiting ${wio.resumeAt}"
    def onExecuted(wio: WIO.Executed[Ctx, Err, Out]): Result             = s"executed: ${recurse(wio.original)}"
    def onDiscarded[In](wio: WIO.Discarded[Ctx, In]): Result             = s"discarded: ${recurse(wio.original)}"

    private def recurseUnique[C <: WorkflowContext, I1, E1, O1 <: WCState[C]](wio: WIO[I1, E1, O1, C]): String =
      recurse(wio)

    private def recurse[C <: WorkflowContext, I1, E1, O1 <: WCState[C]](wio: WIO[I1, E1, O1, C]): String =
      new DescriptionVisitor(wio).run

    def withName(str: String) = s"[${name.getOrElse("")}] ${str}"

    // its safe, compiler sees a runime check where there is none
    @nowarn("msg=the type test for workflows4s.wio.WIO.Embedded")
    private def name: Option[String] = wio match {
      case WIO.Timer(_, _, name, _)           => name
      case WIO.AwaitingTime(_, _)             => none
      case WIO.HandleSignal(_, _, _, m)       => m.operationName
      case WIO.RunIO(_, _, _)                 => none
      case WIO.FlatMap(_, _, _)               => none
      case WIO.Transform(_, _, _)             => none
      case WIO.Pure(_, _)                     => none
      case WIO.End()                          => none
      case WIO.HandleError(_, _, _, _)        => none
      case WIO.HandleErrorWith(_, _, _, _)    => none
      case WIO.Named(_, name, _, _)           => name.some
      case WIO.AndThen(_, _)                  => none
      case WIO.Loop(_, _, _, _, _, _, _)      => none
      case WIO.Fork(_, name, _)               => name
      case WIO.HandleInterruption(_, _, _, _) => none
      case _: WIO.Embedded[?, ?, ?, ?, ?, ?]  => none
    }

  }

}
