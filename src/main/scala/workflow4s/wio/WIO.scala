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
      errorCt: ErrorMeta[Err1],
  ): WIO[Err1, Out1, StateIn, StateOut1] = {
    WIO.FlatMap(this, f, errorCt)
  }

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
  def transformInputState[NewStateIn](
      f: NewStateIn => StateIn,
  ): WIO[Err, Out, NewStateIn, StateOut] = transformState(f, (_, x) => x)
  def transformOutputState[NewStateOut, StIn1 <: StateIn](
      f: (StIn1, StateOut) => NewStateOut,
  ): WIO[Err, Out, StIn1, NewStateOut] = transformState(identity, f)

//  def handleError[Err1, StIn1 <: StateIn, Out1 >: Out, StOut1 >: StateOut, ErrIn >: Err](
//      f: ErrIn => WIO[Err1, Out1, StIn1, StOut1],
//  )(implicit errCt: ClassTag[ErrIn], newErrCt: ClassTag[Err1]): WIO[Err1, Out1, StIn1, StOut1] =
//    WIO.HandleError(this, f, errCt, newErrCt)

  def handleErrorWith[Err1, Out1 >: Out, StOut1 >: StateOut, ErrIn >: Err, StIn0 <: StateIn, StIn1 >: StIn0](
      wio: WIO[Err1, Out1, (StIn1, ErrIn), StOut1],
  )(implicit errCt: ErrorMeta[ErrIn], newErrCt: ErrorMeta[Err1]): WIO[Err1, Out1, StIn0, StOut1] = {
    WIO.HandleErrorWith[Err1, Out1, StIn0, StOut1, Err, StIn1, Out1](this, wio, errCt, newErrCt)
  }

  def named(name: String, description: Option[String] = None): WIO[Err, Out, StateIn, StateOut] = {
    WIO.Named(this, name, description, ErrorMeta.noError)
  }

  def autoNamed[Err1 >: Err](
      description: Option[String] = None,
  )(implicit name: sourcecode.Name, errorCt: ErrorMeta[Err1]): WIO[Err, Out, StateIn, StateOut] = {
    val polishedName = ModelUtils.prettifyName(name.value)
    WIO.Named(this, polishedName, description, errorCt)
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
      errorCt: ErrorMeta[_],
  ) extends WIO[Err, O, StIn, StOut] {
    def expects[Req1, Resp1](@unused signalDef: SignalDef[Req1, Resp1]): Option[HandleSignal[Req1, StIn, StOut, Evt, Resp, Err, Resp1]] = {
      Some(this.asInstanceOf[HandleSignal[Req1, StIn, StOut, Evt, Resp, Err, Resp1]]) // TODO
    }
  }

  case class HandleQuery[+Err, +Out, -StIn, +StOut, -Qr, -QrState, +Resp](
      queryHandler: QueryHandler[Qr, QrState, Resp],
      inner: WIO[Err, Out, StIn, StOut],
  ) extends WIO[Err, Out, StIn, StOut]

  // theoretically state is not needed, it could be State.extract.flatMap(RunIO)
  case class RunIO[-StIn, +StOut, Evt, +O, +Err](
      buildIO: StIn => IO[Evt],
      evtHandler: EventHandler[Evt, StIn, StOut, O, Err],
      errorCt: ErrorMeta[_],
  ) extends WIO[Err, O, StIn, StOut]

  case class FlatMap[Err1 <: Err2, Err2, Out1, +Out2, -StIn, StOut1, +StOut2](
      base: WIO[Err1, Out1, StIn, StOut1],
      getNext: Out1 => WIO[Err2, Out2, StOut1, StOut2],
      errorCt: ErrorMeta[_],
  ) extends WIO[Err2, Out2, StIn, StOut2]

  case class Map[Err, Out1, +Out2, StIn1, -StIn2, StOut1, +StOut2](
      base: WIO[Err, Out1, StIn1, StOut1],
      contramapState: StIn2 => StIn1,
      mapValue: (StIn2, StOut1, Out1) => (StOut2, Out2),
  ) extends WIO[Err, Out2, StIn2, StOut2]

  case class Pure[+Err, +Out, -StIn, +StOut](
      value: StIn => Either[Err, (StOut, Out)],
      errorCt: ErrorMeta[_],
  ) extends WIO[Err, Out, StIn, StOut]

  // TODO this should ne called `Never` or `Halt` or similar, as the workflow cant proceed from that point.
  case class Noop() extends WIO[Nothing, Nothing, Any, Nothing]

  case class HandleError[+ErrOut, +Out, -StIn, +StOut, ErrIn](
      base: WIO[ErrIn, Out, StIn, StOut],
      handleError: ErrIn => WIO[ErrOut, Out, StIn, StOut],
      handledErrorCt: ErrorMeta[_], // used for metadata only
      newErrorCt: ErrorMeta[_],
  ) extends WIO[ErrOut, Out, StIn, StOut]

  case class HandleErrorWith[+ErrOut, +BaseOut, -BaseStIn, +StOut, ErrIn, -HandlerStIn >: BaseStIn, +HandlerOut <: BaseOut](
      base: WIO[ErrIn, BaseOut, BaseStIn, StOut],
      handleError: WIO[ErrOut, HandlerOut, (HandlerStIn, ErrIn), StOut],
      handledErrorMeta: ErrorMeta[_],
      newErrorCt: ErrorMeta[_],
  ) extends WIO[ErrOut, HandlerOut, BaseStIn, StOut]

  case class Named[+Err, +Out, -StIn, +StOut](base: WIO[Err, Out, StIn, StOut], name: String, description: Option[String], errorMeta: ErrorMeta[_])
      extends WIO[Err, Out, StIn, StOut]

  case class AndThen[+Err, Out1, +Out2, -StIn, StOut, +StOut2](first: WIO[Err, Out1, StIn, StOut], second: WIO[Err, Out2, StOut, StOut2])
      extends WIO[Err, Out2, StIn, StOut2]

  // TODO name for condition
  case class DoWhile[Err, Out, StIn, StOut, StOut2](
      loop: WIO[Err, Out, StOut, StOut],
      stopCondition: StOut => Option[StOut2],
      current: WIO[Err, Out, StIn, StOut],
  ) extends WIO[Err, Out, StIn, StOut2]

  case class Fork[+Err, +Out, -StIn, +StOut](branches: Vector[Branch[Err, Out, StIn, StOut]]) extends WIO[Err, Out, StIn, StOut]

  // -----

  case class SignalHandler[-Sig, +Evt, -StIn](handle: (StIn, Sig) => IO[Evt])(implicit sigCt: ClassTag[Sig])              {
    def run[Req, Resp](signal: SignalDef[Req, Resp])(req: Req, s: StIn): Option[IO[Evt]] = {
      sigCt.unapply(req).map(handle(s, _))
    }

    def ct: ClassTag[_] = sigCt
  }
  case class EventHandler[Evt, -StIn, +StOut, +Out, +Err](handle: (StIn, Evt) => Either[Err, (StOut, Out)])(implicit
      val jw: JournalWrite[Evt],
      ct: ClassTag[Evt],
  ) {
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
    def ct: ClassTag[_]                                                              = inCt
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
    def handleEvent[StOut, Out](f: (StIn, Evt) => (StOut, Out)): HandleSignalPartiallyApplied3[Sig, StIn, Evt, Resp, StOut, Nothing, Out] = {
      new HandleSignalPartiallyApplied3(signalDef, signalHandler, EventHandler((s: StIn, e: Evt) => f(s, e).asRight))
    }
    def handleEventWithError[StOut, Err, Out](
        f: (StIn, Evt) => Either[Err, (StOut, Out)],
    ): HandleSignalPartiallyApplied3[Sig, StIn, Evt, Resp, StOut, Err, Out] = {
      new HandleSignalPartiallyApplied3(signalDef, signalHandler, EventHandler((s: StIn, e: Evt) => f(s, e)))
    }
  }
  class HandleSignalPartiallyApplied3[Sig: ClassTag, StIn, Evt: JournalWrite: ClassTag, Resp, StOut, Err, Out](
      signalDef: SignalDef[Sig, Resp],
      signalHandler: SignalHandler[Sig, Evt, StIn],
      eventHandler: EventHandler[Evt, StIn, StOut, Out, Err],
  ) {
    def produceResponse(f: (StIn, Evt) => Resp)(implicit errorCt: ErrorMeta[Err]): WIO[Err, Out, StIn, StOut] = {
      HandleSignal(signalDef, signalHandler, eventHandler, f, errorCt)
    }
  }

  def handleQuery[StIn] = new HandleQueryPartiallyApplied1[StIn]

  class HandleQueryPartiallyApplied1[StIn] {
    def apply[Sig: ClassTag, Resp](@unused signalDef: SignalDef[Sig, Resp])(f: (StIn, Sig) => Resp)(implicit
        ct: ClassTag[StIn],
    ): HandleQueryPartiallyApplied2[StIn, Sig, Resp] = {
      new HandleQueryPartiallyApplied2(f)
    }
  }

  class HandleQueryPartiallyApplied2[QrSt: ClassTag, Sig: ClassTag, Resp](f: (QrSt, Sig) => Resp) {
    def apply[Err, Out, StIn, StOut](wio: WIO[Err, Out, StIn, StOut]): HandleQuery[Err, Out, StIn, StOut, Sig, QrSt, Resp] = {
      WIO.HandleQuery(QueryHandler(f), wio)
    }
  }

  def runIO[State] = new RunIOPartiallyApplied1[State]

  class RunIOPartiallyApplied1[StIn] {
    def apply[Evt: JournalWrite: ClassTag, Resp](f: StIn => IO[Evt]): RunIOPartiallyApplied2[StIn, Evt, Resp] = {
      new RunIOPartiallyApplied2[StIn, Evt, Resp](f)
    }
  }

  class RunIOPartiallyApplied2[StIn, Evt: JournalWrite: ClassTag, Resp](getIO: StIn => IO[Evt]) {
    def handleEvent[StOut](f: (StIn, Evt) => (StOut, Resp)): WIO[Nothing, Resp, StIn, StOut]                                                         = {
      RunIO(getIO, EventHandler((s, e: Evt) => f(s, e).asRight), ErrorMeta.noError)
    }
    def handleEventWithError[StOut, Err](f: (StIn, Evt) => Either[Err, (StOut, Resp)])(implicit errorCt: ErrorMeta[Err]): WIO[Err, Resp, StIn, StOut] = {
      RunIO(getIO, EventHandler(f), errorCt)
    }
  }

  def getState[St]: WIO[Nothing, St, St, St]            = WIO.Pure(s => (s, s).asRight, ErrorMeta.noError)
  def setState[St](st: St): WIO[Nothing, Unit, Any, St] = WIO.Pure(_ => (st, ()).asRight, ErrorMeta.noError)

  def await[St](duration: Duration): WIO[Nothing, Unit, St, St] = ???

  def pure[St]: PurePartiallyApplied[St] = new PurePartiallyApplied
  class PurePartiallyApplied[StIn] {
    def apply[O](value: O): WIO[Nothing, O, StIn, StIn]                                                = WIO.Pure(s => Right(s -> value), ErrorMeta.noError)
    def state[StOut](value: StOut): WIO[Nothing, Unit, StIn, StOut]                                    = WIO.Pure(s => Right(value -> ()), ErrorMeta.noError)
    def make[O](f: StIn => O): WIO[Nothing, O, StIn, StIn]                                             = WIO.Pure(s => Right(s -> f(s)), ErrorMeta.noError)
    def makeState[StOut](f: StIn => StOut): WIO[Nothing, Unit, StIn, StOut]                            = WIO.Pure(s => Right(f(s) -> ()), ErrorMeta.noError)
    def makeError[Err](f: StIn => Option[Err])(implicit ct: ErrorMeta[Err]): WIO[Err, Unit, StIn, StIn] = {
      WIO.Pure(s => f(s).map(Left(_)).getOrElse(Right(s, ())), ct)
    }
  }

  def unit[St] = pure[St](())

  def raise[St]: RaisePartiallyApplied[St] = new RaisePartiallyApplied
  class RaisePartiallyApplied[StIn] {
    def apply[Err](value: Err)(implicit ct: ClassTag[Err]): WIO[Err, Nothing, StIn, Nothing] = WIO.Pure(s => Left(value), ErrorMeta.noError)
  }

  def repeat[Err, Out, State](action: WIO[Err, Out, State, State]) = RepeatBuilder(action)

  case class RepeatBuilder[Err, Out, State](action: WIO[Err, Out, State, State]) {
    def untilSome[StOut](f: State => Option[StOut]): WIO[Err, Out, State, StOut] = WIO.DoWhile(action, f, action)
  }

  def fork[State]: ForkBuilder[Nothing, Nothing, State, Nothing] = ForkBuilder(Vector())

  trait Branch[+Err, +Out, -StIn, +StOut] {
    type I // Intermediate
    def condition: StIn => Option[I]
    def wio: WIO[Err, Out, (StIn, I), StOut]
  }
  object Branch                           {
    def apply[State, T, Err, Out, StOut](cond: State => Option[T], wio0: WIO[Err, Out, (State, T), StOut]): Branch[Err, Out, State, StOut] = {
      new Branch[Err, Out, State, StOut] {
        override type I = T
        override def condition: State => Option[I]         = cond
        override def wio: WIO[Err, Out, (State, I), StOut] = wio0
      }
    }
  }

  // can be removed and replaced with direct instance of WIO.Fork?
  case class ForkBuilder[+Err, +Out, -StIn, +StOut](branches: Vector[Branch[Err, Out, StIn, StOut]]) {
    def branch[T, Err1 >: Err, Out1 >: Out, StOu1 >: StOut, StIn1 <: StIn](cond: StIn1 => Option[T])(
        wio: WIO[Err1, Out1, (StIn1, T), StOu1],
    ): ForkBuilder[Err1, Out1, StIn1, StOu1] = addBranch(Branch(cond, wio))

    def addBranch[T, Err1 >: Err, Out1 >: Out, StOu1 >: StOut, StIn1 <: StIn](
        b: Branch[Err1, Out1, StIn1, StOu1],
    ): ForkBuilder[Err1, Out1, StIn1, StOu1] = {
      ForkBuilder(
        branches.appended(b),
      )
    }

    def done: WIO[Err, Out, StIn, StOut] = WIO.Fork(branches)
  }

  def branch[State]: BranchBuilder[State] = BranchBuilder[State]()

  case class BranchBuilder[State]() {
    def when[Err, Out, StOut](cond: State => Boolean)(
        wio: WIO[Err, Out, State, StOut],
    ): Branch[Err, Out, State, StOut] = Branch(cond.andThen(Option.when(_)(())), wio.transformInputState((x: (State, Any)) => x._1))
    def create[T, Err, Out, StOut](cond: State => Option[T])(
        wio: WIO[Err, Out, (State, T), StOut],
    ): Branch[Err, Out, State, StOut] = Branch(cond, wio)
  }

  sealed trait NoError
  val NoErrorClassTag: ClassTag[NoError] = implicitly[ClassTag[NoError]]
}
