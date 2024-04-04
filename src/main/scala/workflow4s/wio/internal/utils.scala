package workflow4s.wio.internal

import cats.effect.IO
import workflow4s.wio.SignalDef

import scala.reflect.ClassTag
import scala.util.chaining.scalaUtilChainingOps

// X => IO[ChecksStarted],           ChecksEvent => Option[ChecksStarted],                CheckStarted => ChecksState
// X => IO[WithdrawalChecksStarted], WithdrawalEvent => Option[WithdrawalChecksStarted],  WithdrawalChecksStarted => WithdrawalState.Checking
// ChecksStarted => WithdrawalChecksStarted
// WithdrawalChecksStarted => ChecksStarted
// ChecksState => > WithdrawalState.Checking
// WithdrawalEvent => ChecksEvent

trait EventHandler[-In, +Out, EventBase, Evt] { parent =>

  def detect: EventBase => Option[Evt]
  def convert: Evt => EventBase

  def handle: (In, Evt) => Out

  def xmapEvt[E1](contramap: E1 => Option[EventBase], mapf: EventBase => E1): EventHandler[In, Out, E1, Evt] =
    new EventHandler[In, Out, E1, Evt] {
      override def detect: E1 => Option[Evt] = contramap.andThen(_.flatMap(parent.detect))
      def convert: Evt => E1                 = parent.convert.andThen(mapf)
      override def handle: (In, Evt) => Out  = parent.handle
    }

  def map[O1](f: Out => O1): EventHandler[In, O1, EventBase, Evt] =
    new EventHandler[In, O1, EventBase, Evt] {
      override def detect: EventBase => Option[Evt] = parent.detect
      def convert: Evt => EventBase                 = parent.convert
      override def handle: (In, Evt) => O1          = (i, e) => parent.handle(i, e).pipe(f)
    }
}

object EventHandler {
  def apply[EventBase, In, Out, Evt](
      detect0: EventBase => Option[Evt],
      convert0: Evt => EventBase,
      handle0: (In, Evt) => Out,
  ): EventHandler[In, Out, EventBase, Evt] = new EventHandler[In, Out, EventBase, Evt] {
    override def detect: EventBase => Option[Evt] = detect0
    override def handle: (In, Evt) => Out         = handle0
    override def convert: Evt => EventBase = convert0
  }
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

case class SignalHandler[-Sig, +Evt, -In](handle: (In, Sig) => IO[Evt])(implicit sigCt: ClassTag[Sig]) {
  def run[Req, Resp](signal: SignalDef[Req, Resp])(req: Req, in: In): Option[IO[Evt]] = {
    sigCt.unapply(req).map(handle(in, _))
  }

  def map[E1](f: Evt => E1): SignalHandler[Sig, E1, In] = SignalHandler((in, sig) => handle(in, sig).map(f))

  def ct: ClassTag[_] = sigCt
}
