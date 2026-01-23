package workflows4s.wio.internal

import cats.effect.IO

import scala.reflect.ClassTag
import scala.util.chaining.scalaUtilChainingOps

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
}

trait TypedEventHandler[-In, +Out, EventBase, Evt] extends EventHandler[In, Out, EventBase, Evt] {
  def matchedClass: ClassTag[?]
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

  def typed[EventBase, In, Out, Evt](
      convert0: Evt => EventBase,
      handle0: (In, Evt) => Out,
      matchedClass0: ClassTag[Evt],
  ): TypedEventHandler[In, Out, EventBase, Evt] = new TypedEventHandler[In, Out, EventBase, Evt] {
    override def matchedClass: ClassTag[Evt]      = matchedClass0
    override def handle: (In, Evt) => Out         = handle0
    override def convert: Evt => EventBase        = convert0
    override def detect: EventBase => Option[Evt] = matchedClass0.unapply
  }

  def partial[EventBase, In, Out, Evt](
      convert0: Evt => EventBase,
      handle0: (In, Evt) => Out,
  )(using ct: ClassTag[Evt]): TypedEventHandler[In, Out, EventBase, Evt] =
    typed(convert0, handle0, ct)
}

case class SignalHandler[-Sig, +Evt, -In](handle: (In, Sig) => IO[Evt]) {
  def map[E1](f: Evt => E1): SignalHandler[Sig, E1, In] = SignalHandler((in, sig) => handle(in, sig).map(f))
}
