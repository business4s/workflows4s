package workflows4s.wio.internal

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

/** SignalHandler stores the effect function type-erased to avoid coupling to a specific effect type.
  *
  * The actual type is `(In, Sig) => F[Evt]` where F is the context's effect type.
  */
case class SignalHandler[-Sig, +Evt, -In](handle: (In, Sig) => Any) {
  // Note: map requires knowledge of the effect type, so it's moved to the evaluator
}
