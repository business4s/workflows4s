package workflow4s.wio.internal

import cats.effect.IO
import cats.syntax.all._
import workflow4s.wio.Interpreter.SignalResponse
import workflow4s.wio._

trait CurrentStateEvaluatorModule extends VisitorModule {
  import c.WIO

  object CurrentStateEvaluator {

    def getCurrentStateDescription(
        wio: WIO[?, ?, ?],
    ): String = {
      val visitor = new DescriptionVisitor(wio)
      visitor.run
    }

    private class DescriptionVisitor[In, Err, Out](wio: WIO[In, Err, Out]) extends Visitor[In, Err, Out](wio) {
      override type Result = String
      
      def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[In, Out, Err, Sig, Resp, Evt]): Result           =
        s"Expects signal ${wio.sigHandler.ct.runtimeClass.getSimpleName}"
      def onRunIO[Evt](wio: WIO.RunIO[In, Err, Out, Evt]): Result                                         = "Awaits IO execution"
      def onFlatMap[Out1, Err1 <: Err](wio: WIO.FlatMap[Err1, Err, Out1, Out, In]): Result                = recurse(wio.base)
      def onMap[In1, Out1](wio: WIO.Map[In1, Err, Out1, In, Out]): Result                                 = recurse(wio.base)
      def onHandleQuery[Qr, QrState, Resp](wio: WIO.HandleQuery[In, Err, Out, Qr, QrState, Resp]): Result =
        s"(Expects query ${wio.queryHandler.ct.runtimeClass.getSimpleName} or ${recurse(wio.inner)})"
      def onNoop(wio: WIO.Noop): Result                                                                   = "Noop"
      def onNamed(wio: WIO.Named[In, Err, Out]): Result                                                   = recurse(wio.base)
      def onHandleError[ErrIn](wio: WIO.HandleError[In, Err, Out, ErrIn]): Result                         = s"(Handle error or ${recurse(wio.base)})"
      def onHandleErrorWith[ErrIn](wio: WIO.HandleErrorWith[In, ErrIn, Out, Err]): Result                 =
        s"(${recurse(wio.base)}, on error: ${recurse(wio.handleError)}"
      def onAndThen[Out1](wio: WIO.AndThen[In, Err, Out1, Out]): Result                                   = recurse(wio.first)
      def onPure(wio: WIO.Pure[In, Err, Out]): Result                                                     = "pure"
      def onDoWhile[Out1](wio: WIO.DoWhile[In, Err, Out1, Out]): Result                                   = s"do-while; current = ${recurse(wio.current)}"
      def onFork(wio: WIO.Fork[In, Err, Out]): Result                                                     = "fork"

      private def recurse[I1, E1, O1, SIn1, SOut1](wio: WIO[I1, E1, O1]): String = new DescriptionVisitor(wio).run

    }

  }
}
