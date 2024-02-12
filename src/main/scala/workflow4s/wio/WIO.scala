package workflow4s.wio

import cats.effect.IO
import scala.annotation.unused

sealed trait WIO[+Err, +Out, State]

object WIO {

  type Total[St] = WIO[Nothing, Any, St]

  case class HandleSignal[Sig, St, Evt, O](sigHandler: SignalHandler[Sig, Evt, St], evtHandler: EventHandler[Evt, St, O])
      extends WIO[Nothing, O, St] {
    def expects[Req, Resp](@unused signalDef: SignalDef[Req, Resp]): Option[HandleSignal[Req, St, Evt, Resp]] =
      Some(this.asInstanceOf[HandleSignal[Req, St, Evt, Resp]]) // TODO
  }

  case class HandleQuery[Qr, St, O](queryHandler: QueryHandler[Qr, St, O]) extends WIO[Nothing, O, St] {
    def expects[Req, Resp](@unused signalDef: SignalDef[Req, Resp]): Option[HandleQuery[Req, St, Resp]] =
      Some(this.asInstanceOf[HandleQuery[Req, St, Resp]]) // TODO
  }

  case class Or[Err, Out, State](first: WIO[Err, Out, State], second: WIO[Err, Out, State]) extends WIO[Err, Out, State]

  case class Noop[St]() extends WIO[Nothing, Unit, St]

  case class SignalHandler[Sig, Evt, St](handle: (St, Sig) => IO[Evt])
  case class EventHandler[Evt, St, Out](handle: (St, Evt) => (St, Out))(implicit val jw: JournalWrite[Evt])
  case class QueryHandler[Qr, St, Out](handle: (St, Qr) => Out)

  def handleSignal[State] = new HandleSignalPartiallyApplied1[State]

  class HandleSignalPartiallyApplied1[St] {
    def apply[Sig, Evt: JournalWrite, Resp](@unused signalDef: SignalDef[Sig, Resp])(
        f: (St, Sig) => IO[Evt],
    ): HandleSignalPartiallyApplied2[Sig, St, Evt, Resp] = new HandleSignalPartiallyApplied2[Sig, St, Evt, Resp](f)
  }

  class HandleSignalPartiallyApplied2[Sig, St, Evt: JournalWrite, Resp](handleSignal: (St, Sig) => IO[Evt]) {
    def handleEvent(f: (St, Evt) => (St, Resp)): WIO[Nothing, Resp, St] = HandleSignal(SignalHandler(handleSignal), EventHandler(f))
  }

  def handleQuery[State] = new HandleQueryPartiallyApplied1[State]

  class HandleQueryPartiallyApplied1[St] {
    def apply[Sig, Resp](@unused signalDef: SignalDef[Sig, Resp])(
        f: (St, Sig) => Resp,
    ): HandleQuery[Sig, St, Resp] = WIO.HandleQuery(QueryHandler(f))
  }

  def par[Err, Out, State](first: WIO[Err, Out, State], second: WIO[Err, Out, State]) = Or(first, second)

}
