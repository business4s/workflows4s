package workflow4s.wio.internal

import cats.effect.IO
import cats.syntax.all._
import workflow4s.wio.Interpreter.{SignalResponse, Visitor}
import workflow4s.wio.WIO.HandleSignal
import workflow4s.wio._

object CurrentStateEvaluator {

  def getCurrentStateDescription[Err, O, StateIn, StateOut](
      wio: WIO[Err, O, StateIn, StateOut],
      state: StateIn,
  ): String = {
    val visitor = new DescriptionVisitor
    visitor.dispatch(wio, state).merge
  }

  class DescriptionVisitor extends Visitor {
    type DirectOut[StOut, O]        = String
    type FlatMapOut[Err, Out, SOut] = String

    override def onRunIO[StIn, StOut, Evt, O](wio: WIO.RunIO[StIn, StOut, Evt, O], state: StIn): DirectOut[StOut, O] = "Awaits IO execution"

    def onHandleQuery[Err, Out, StIn, StOut, Qr, QrSt, Resp](
        wio: WIO.HandleQuery[Err, Out, StIn, StOut, Qr, QrSt, Resp],
        state: StIn,
    ): DispatchResult[Err, Out, StOut] =
      s"(Expects query ${wio.queryHandler.ct.runtimeClass.getSimpleName} or ${dispatch(wio.inner, state)}".asLeft

    override def onSignal[Sig, StIn, StOut, Evt, O](wio: HandleSignal[Sig, StIn, StOut, Evt, O], state: StIn): String =
      s"Expects signal ${wio.sigHandler.ct.runtimeClass.getSimpleName}"

    override def onNoop[St, O](wio: WIO.Noop): DirectOut[St, O] = "Empty"

    override def onFlatMap[Err, Out1, Out2, S0, S1, S2](wio: WIO.FlatMap[Err, Out1, Out2, S0, S1, S2], state: S0): FlatMapOut[Err, Out1, S2] =
      dispatch(wio.base, state).merge

    override def onMap[Err, Out1, Out2, StIn, StOut](
        wio: WIO.Map[Err, Out1, Out2, StIn, StOut],
        state: StIn,
    ): Either[DirectOut[StOut, Out2], FlatMapOut[Err, Out2, StOut]] =
      dispatch[Err, Out1, StIn, StOut](wio.base, state)

  }

}
