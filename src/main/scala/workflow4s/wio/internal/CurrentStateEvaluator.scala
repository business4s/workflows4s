package workflow4s.wio.internal

import cats.effect.IO
import cats.syntax.all._
import workflow4s.wio.Interpreter.{SignalResponse, Visitor}
import workflow4s.wio.WIO.HandleSignal
import workflow4s.wio._

object CurrentStateEvaluator {

  def getCurrentStateDescription[Err, O, StateIn, StateOut](
      wio: WIO[Err, O, StateIn, StateOut],
  ): String = {
    val visitor = new DescriptionVisitor(wio)
    visitor.run
  }

  class DescriptionVisitor[Err, Out, StIn, StOut](wio: WIO[Err, Out, StIn, StOut]) extends Visitor[Err, Out, StIn, StOut](wio) {
    override type DispatchResult = String

    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[Sig, StIn, StOut, Evt, Out, Err, Resp]): DispatchResult =
      s"Expects signal ${wio.sigHandler.ct.runtimeClass.getSimpleName}"

    def onRunIO[Evt](wio: WIO.RunIO[StIn, StOut, Evt, Out, Err]): DispatchResult =
      "Awaits IO execution"

    def onFlatMap[Out1, StOut1, Err1 <: Err](wio: WIO.FlatMap[Err1, Err, Out1, Out, StIn, StOut1, StOut]): DispatchResult = {
      s"(${recurse(wio.base)} and more)"
    }
    def onMap[Out1](wio: WIO.Map[Err, Out1, Out, StIn, StOut]): DispatchResult = {
      recurse(wio.base)
    }
    def onHandleQuery[Qr, QrSt, Resp](wio: WIO.HandleQuery[Err, Out, StIn, StOut, Qr, QrSt, Resp]): DispatchResult = {
      s"(Expects query ${wio.queryHandler.ct.runtimeClass.getSimpleName} or ${recurse(wio.inner)})"
    }

    def onNoop(wio: WIO.Noop): DispatchResult = "Noop"

    override def onNamed(wio: WIO.Named[Err, Out, StIn, StOut]): DispatchResult = recurse(wio.base)

    override def onAndThen[Out1, StOut1](wio: WIO.AndThen[Err, Out1, Out, StIn, StOut1, StOut]): String =
      s"(${recurse(wio.first)} and then ${recurse(wio.second)})"

    override def onPure(wio: WIO.Pure[Err, Out, StIn, StOut]): String = "pure"

    override def onHandleError[ErrIn <: Err](wio: WIO.HandleError[Err, Out, StIn, StOut, ErrIn]): DispatchResult =
      s"(Handle error or ${recurse(wio.base)})"

    private def recurse[E1, O1, SIn1, SOut1](wio: WIO[E1, O1, SIn1, SOut1]): String = new DescriptionVisitor(wio).run

  }

}
