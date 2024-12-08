package workflow4s.wio.internal

import scala.reflect.ClassTag
import scala.util.chaining.scalaUtilChainingOps

import cats.effect.IO
import workflow4s.wio.SignalDef

trait EventHandler[-In, +Out, EventBase, Evt] { parent =>

  def detect: EventBase => Option[Evt]
  def convert: Evt => EventBase

  def handle: (In, Evt) => Out

  def map[O1](f: Out => O1): EventHandler[In, O1, EventBase, Evt] =
    new EventHandler[In, O1, EventBase, Evt] {
      override def detect: EventBase => Option[Evt] = parent.detect
      def convert: Evt => EventBase                 = parent.convert
      override def handle: (In, Evt) => O1          = (i, e) => parent.handle(i, e).pipe(f)
    }

  def captureIn(in: In): EventHandler[Any, Out, EventBase, Evt] = new EventHandler[Any, Out, EventBase, Evt] {
    override def detect: EventBase => Option[Evt] = parent.detect
    def convert: Evt => EventBase                 = parent.convert
    override def handle: (Any, Evt) => Out        = (_, e) => parent.handle(in, e)
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
    override def convert: Evt => EventBase        = convert0
  }
}

case class SignalHandler[-Sig, +Evt, -In](handle: (In, Sig) => IO[Evt])(using sigCt: ClassTag[Sig]) {
  def run[Req, Resp](signal: SignalDef[Req, Resp])(req: Req, in: In): Option[IO[Evt]] = {
    sigCt.unapply(req).map(handle(in, _)) // TODO, something fishy here, Sig and Req should be a single thing
  }

  def map[E1](f: Evt => E1): SignalHandler[Sig, E1, In] = SignalHandler((in, sig) => handle(in, sig).map(f))

  def ct: ClassTag[?] = sigCt
}
