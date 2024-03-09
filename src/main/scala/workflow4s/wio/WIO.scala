package workflow4s.wio

import cats.effect.IO
import cats.implicits.{catsSyntaxEitherId, catsSyntaxOptionId}
import workflow4s.wio.model.ModelUtils

import scala.annotation.unused
import scala.concurrent.duration.Duration
import scala.language.implicitConversions
import scala.reflect.ClassTag

sealed trait WIO[+Err, +Out, -StateIn, +StateOut] {

  def flatMap[Err1 >: Err, StateOut1, Out1](f: Out => WIO[Err1, Out1, StateOut, StateOut1])(implicit
      errorCt: ClassTag[Err1],
  ): WIO[Err1, Out1, StateIn, StateOut1] =
    WIO.FlatMap(this, f, errorCt)

  def map[Out1](f: Out => Out1): WIO[Err, Out1, StateIn, StateOut] = WIO.Map(
    this,
    identity[StateIn],
    (sIn: StateIn, sOut: StateOut, out: Out) => (sOut, f(out)),
  )

  // TODO, variance can be fooled if moved to extension method
  def checkpointed[Evt, O1, StIn1 <: StateIn, StOut1 >: StateOut](genEvent: (StateOut, Out) => Evt)(
      handleEvent: (StIn1, Evt) => (StOut1, O1),
  ): WIO[Err, O1, StateIn, StateOut] = ???

  def transformState[NewStateIn, NewStateOut](
      f: NewStateIn => StateIn,
      g: (NewStateIn, StateOut) => NewStateOut,
  ): WIO[Err, Out, NewStateIn, NewStateOut] = WIO.Map[Err, Out, Out, StateIn, NewStateIn, StateOut, NewStateOut](
    this,
    f,
    (sIn: NewStateIn, sOut: StateOut, o: Out) => g(sIn, sOut) -> o,
  )

  def handleError[Err1, StIn1 <: StateIn, Out1 >: Out, StOut1 >: StateOut, ErrIn >: Err](
      f: ErrIn => WIO[Err1, Out1, StIn1, StOut1],
  )(implicit errCt: ClassTag[ErrIn], newErrCt: ClassTag[Err1]): WIO[Err1, Out1, StIn1, StOut1] =
    WIO.HandleError(this, f, errCt, newErrCt)

  def named(name: String, description: Option[String] = None): WIO[Err, Out, StateIn, StateOut] = WIO.Named(this, name, description, None)

  def autoNamed[Err1 >: Err](
      description: Option[String] = None,
  )(implicit name: sourcecode.Name, errorCt: ClassTag[Err1]): WIO[Err, Out, StateIn, StateOut] = {
    val polishedName = ModelUtils.prettifyName(name.value)
    WIO.Named(this, polishedName, description, errorCt.runtimeClass.getSimpleName.some)
  }

  def andThen[Err1 >: Err, StateOut1, Out1](next: WIO[Err1, Out1, StateOut, StateOut1]): WIO[Err1, Out1, StateIn, StateOut1] = WIO.AndThen(this, next)
  def >>>[Err1 >: Err, StateOut1, Out1](next: WIO[Err1, Out1, StateOut, StateOut1]): WIO[Err1, Out1, StateIn, StateOut1]     = andThen(next)

}

object WIO {

  type Total[St]           = WIO[Any, Any, St, Any]
  type States[StIn, StOut] = WIO[Any, Any, StIn, StOut]
  type Opaque              = WIO[Any, Any, Nothing, Any]

  case class HandleSignal[Sig, -StIn, +StOut, Evt, +O, +Err, Resp](
      sigDef: SignalDef[Sig, Resp],
      sigHandler: SignalHandler[Sig, Evt, StIn],
      evtHandler: EventHandler[Evt, StIn, StOut, O, Err],
      getResp: (StIn, Evt) => Resp,
      errorCt: ClassTag[_],
  ) extends WIO[Err, O, StIn, StOut] {
    def expects[Req1, Resp1](@unused signalDef: SignalDef[Req1, Resp1]): Option[HandleSignal[Req1, StIn, StOut, Evt, Resp, Err, Resp1]] =
      Some(this.asInstanceOf[HandleSignal[Req1, StIn, StOut, Evt, Resp, Err, Resp1]]) // TODO
  }

  case class HandleQuery[+Err, +Out, -StIn, +StOut, -Qr, -QrState, +Resp](
      queryHandler: QueryHandler[Qr, QrState, Resp],
      inner: WIO[Err, Out, StIn, StOut],
  ) extends WIO[Err, Out, StIn, StOut]

  // theoretically state is not needed, it could be State.extract.flatMap(RunIO)
  case class RunIO[-StIn, +StOut, Evt, +O, +Err](
      buildIO: StIn => IO[Evt],
      evtHandler: EventHandler[Evt, StIn, StOut, O, Err],
      errorCt: ClassTag[_],
  ) extends WIO[Err, O, StIn, StOut]

  case class FlatMap[Err1, Err2 >: Err1, Out1, +Out2, -StIn, StOut1, +StOut2](
      base: WIO[Err1, Out1, StIn, StOut1],
      getNext: Out1 => WIO[Err2, Out2, StOut1, StOut2],
      errorCt: ClassTag[_],
  ) extends WIO[Err2, Out2, StIn, StOut2]

  case class Map[Err, Out1, +Out2, StIn1, -StIn2, StOut1, +StOut2](
      base: WIO[Err, Out1, StIn1, StOut1],
      contramapState: StIn2 => StIn1,
      mapValue: (StIn2, StOut1, Out1) => (StOut2, Out2),
  ) extends WIO[Err, Out2, StIn2, StOut2]

  case class Pure[+Err, +Out, -StIn, +StOut](
      value: StIn => Either[Err, (StOut, Out)],
      errorCt: Option[ClassTag[_]],
  ) extends WIO[Err, Out, StIn, StOut]

  // TODO this should ne called `Never` or `Halt` or similar, as the workflow cant proceed from that point.
  case class Noop() extends WIO[Nothing, Nothing, Any, Nothing]

  case class HandleError[+ErrOut, +Out, -StIn, +StOut, ErrIn](
      base: WIO[ErrIn, Out, StIn, StOut],
      handleError: ErrIn => WIO[ErrOut, Out, StIn, StOut],
      handledErrorCt: ClassTag[_], // used for metadata only
      newErrorCt: ClassTag[_],
  ) extends WIO[ErrOut, Out, StIn, StOut]

  case class Named[+Err, +Out, -StIn, +StOut](base: WIO[Err, Out, StIn, StOut], name: String, description: Option[String], errorName: Option[String])
      extends WIO[Err, Out, StIn, StOut]

  case class AndThen[+Err, Out1, +Out2, -StIn, StOut, +StOut2](first: WIO[Err, Out1, StIn, StOut], second: WIO[Err, Out2, StOut, StOut2])
      extends WIO[Err, Out2, StIn, StOut2]

  // -----

  case class SignalHandler[-Sig, +Evt, -StIn](handle: (StIn, Sig) => IO[Evt])(implicit sigCt: ClassTag[Sig])              {
    def run[Req, Resp](signal: SignalDef[Req, Resp])(req: Req, s: StIn): Option[IO[Evt]] =
      sigCt.unapply(req).map(handle(s, _))

    def ct: ClassTag[_] = sigCt
  }
  case class EventHandler[Evt, -StIn, +StOut, +Out, +Err](handle: (StIn, Evt) => Either[Err, (StOut, Out)])(implicit
      val jw: JournalWrite[Evt],
      ct: ClassTag[Evt],
  )                                                                                                                       {
    def expects(any: Any): Option[Evt] = ct.unapply(any)
  }
  case class QueryHandler[-Qr, -StIn, +Out](handle: (StIn, Qr) => Out)(implicit inCt: ClassTag[Qr], stCt: ClassTag[StIn]) {
    def run[Req, Resp](signal: SignalDef[Req, Resp])(req: Req, s: Any): Option[Resp] = {
      // thats not great but how to do better?
      for {
        qr    <- inCt.unapply(req)
        stIn  <- stCt.unapply(s)
        result = handle(stIn, qr)
        resp  <- signal.respCt.unapply(result)
      } yield resp
    }
    def ct: ClassTag[_] = inCt
  }

  def handleSignal[StIn] = new HandleSignalPartiallyApplied1[StIn]

  class HandleSignalPartiallyApplied1[StIn] {
    def apply[Sig: ClassTag, Evt: JournalWrite: ClassTag, Resp](@unused signalDef: SignalDef[Sig, Resp])(
        f: (StIn, Sig) => IO[Evt],
    ): HandleSignalPartiallyApplied2[Sig, StIn, Evt, Resp] = new HandleSignalPartiallyApplied2[Sig, StIn, Evt, Resp](SignalHandler(f), signalDef)
  }

  class HandleSignalPartiallyApplied2[Sig: ClassTag, StIn, Evt: JournalWrite: ClassTag, Resp](
      signalHandler: SignalHandler[Sig, Evt, StIn],
      signalDef: SignalDef[Sig, Resp],
  ) {
    def handleEvent[StOut, Out](f: (StIn, Evt) => (StOut, Out)): HandleSignalPartiallyApplied3[Sig, StIn, Evt, Resp, StOut, Nothing, Out] =
      new HandleSignalPartiallyApplied3(signalDef, signalHandler, EventHandler((s: StIn, e: Evt) => f(s, e).asRight))
    def handleEventWithError[StOut, Err, Out](
        f: (StIn, Evt) => Either[Err, (StOut, Out)],
    ): HandleSignalPartiallyApplied3[Sig, StIn, Evt, Resp, StOut, Err, Out]                                                               =
      new HandleSignalPartiallyApplied3(signalDef, signalHandler, EventHandler((s: StIn, e: Evt) => f(s, e)))
  }
  class HandleSignalPartiallyApplied3[Sig: ClassTag, StIn, Evt: JournalWrite: ClassTag, Resp, StOut, Err, Out](
      signalDef: SignalDef[Sig, Resp],
      signalHandler: SignalHandler[Sig, Evt, StIn],
      eventHandler: EventHandler[Evt, StIn, StOut, Out, Err],
  ) {
    def produceResponse(f: (StIn, Evt) => Resp)(implicit errorCt: ClassTag[Err]): WIO[Err, Out, StIn, StOut] =
      HandleSignal(signalDef, signalHandler, eventHandler, f, errorCt)
  }

  def handleQuery[StIn] = new HandleQueryPartiallyApplied1[StIn]

  class HandleQueryPartiallyApplied1[StIn] {
    def apply[Sig: ClassTag, Resp](@unused signalDef: SignalDef[Sig, Resp])(f: (StIn, Sig) => Resp)(implicit
        ct: ClassTag[StIn],
    ): HandleQueryPartiallyApplied2[StIn, Sig, Resp] =
      new HandleQueryPartiallyApplied2(f)
  }

  class HandleQueryPartiallyApplied2[QrSt: ClassTag, Sig: ClassTag, Resp](f: (QrSt, Sig) => Resp) {
    def apply[Err, Out, StIn, StOut](wio: WIO[Err, Out, StIn, StOut]): HandleQuery[Err, Out, StIn, StOut, Sig, QrSt, Resp] =
      WIO.HandleQuery(QueryHandler(f), wio)
  }

  def runIO[State] = new RunIOPartiallyApplied1[State]

  class RunIOPartiallyApplied1[StIn] {
    def apply[Evt: JournalWrite: ClassTag, Resp](f: StIn => IO[Evt]): RunIOPartiallyApplied2[StIn, Evt, Resp] =
      new RunIOPartiallyApplied2[StIn, Evt, Resp](f)
  }

  class RunIOPartiallyApplied2[StIn, Evt: JournalWrite: ClassTag, Resp](getIO: StIn => IO[Evt]) {
    def handleEvent[StOut](f: (StIn, Evt) => (StOut, Resp)): WIO[Nothing, Resp, StIn, StOut]                                                         =
      RunIO(getIO, EventHandler((s, e: Evt) => f(s, e).asRight), implicitly[ClassTag[Nothing]])
    def handleEventWithError[StOut, Err](f: (StIn, Evt) => Either[Err, (StOut, Resp)])(implicit errorCt: ClassTag[Err]): WIO[Err, Resp, StIn, StOut] =
      RunIO(getIO, EventHandler(f), errorCt)
  }

  def getState[St]: WIO[Nothing, St, St, St]            = WIO.Pure(s => (s, s).asRight, None)
  def setState[St](st: St): WIO[Nothing, Unit, Any, St] = WIO.Pure(_ => (st, ()).asRight, None)

  def await[St](duration: Duration): WIO[Nothing, Unit, St, St] = ???

  def pure[St]: PurePartiallyApplied[St] = new PurePartiallyApplied
  class PurePartiallyApplied[StIn] {
    def apply[O](value: O): WIO[Nothing, O, StIn, StIn] = WIO.Pure(s => Right(s -> value), None)
  }

  def unit[St] = pure[St](())

  def raise[St]: RaisePartiallyApplied[St] = new RaisePartiallyApplied
  class RaisePartiallyApplied[StIn] {
    def apply[Err](value: Err)(implicit ct: ClassTag[Err]): WIO[Err, Nothing, StIn, Nothing] = WIO.Pure(s => Left(value), None)
  }

}
