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
    visitor.run(null).merge
  }

  class DescriptionVisitor[Err, Out, StIn, StOut](wio: WIO[Err, Out, StIn, StOut]) extends Visitor[Err, Out, StIn, StOut](wio) {
    type DirectOut = String
    type FlatMapOut= String

    def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[Sig, StIn, StOut, Evt, Out, Err, Resp], state: StIn): DirectOut =
      s"Expects signal ${wio.sigHandler.ct.runtimeClass.getSimpleName}"

    def onRunIO[Evt](wio: WIO.RunIO[StIn, StOut, Evt, Out, Err], state: StIn): DirectOut =
      "Awaits IO execution"

    def onFlatMap[Out1, StOut1](wio: WIO.FlatMap[Err, Out1, Out, StIn, StOut1, StOut], state: StIn): FlatMapOut = {
      val visitor = new DescriptionVisitor(wio.base)
      s"(${visitor.run(state.asRight).merge} and more)"
    }
    def onMap[Out1](wio: WIO.Map[Err, Out1, Out, StIn, StOut], state: StIn): DispatchResult = {
      val visitor = new DescriptionVisitor(wio.base)
      visitor.run(state.asRight)
    }
    def onHandleQuery[Qr, QrSt, Resp](wio: WIO.HandleQuery[Err, Out, StIn, StOut, Qr, QrSt, Resp], state: StIn): DispatchResult = {
      val visitor = new DescriptionVisitor(wio.inner)
      s"(Expects query ${wio.queryHandler.ct.runtimeClass.getSimpleName} or ${visitor.run(state.asRight).merge})".asLeft
    }

    def onNoop(wio: WIO.Noop): DirectOut = "Noop"

  }

}
