package workflows4s.example

import workflows4s.wio.WorkflowContext

import java.time.Instant

/** Example package demonstrating workflow definitions with cats-effect IO.
  *
  * The WorkflowContext defines the effect type `F[A] = cats.effect.IO[A]`. The `HasEffect` mechanism extracts this type and uses it for effect-based
  * operations like `runIO`, `retry`, and signal handling.
  *
  * The Effect[IO] type class instance is provided by the workflows4s-cats-effect module.
  */
package object docs {

  sealed trait MyEventBase
  case class MyEvent()                      extends MyEventBase
  case class MyTimerStarted(time: Instant)  extends MyEventBase
  case class MyTimerReleased(time: Instant) extends MyEventBase
  case class MyState(counter: Int)
  case class MyError()
  case class MyRequest()
  case class MyResponse()

  /** Example WorkflowContext with cats-effect IO as the effect type.
    *
    * The effect type `F[A] = cats.effect.IO[A]` is extracted at compile time by the `HasEffect` macro, enabling effect-polymorphic workflow
    * definitions.
    */
  object Context extends WorkflowContext {
    override type Event = MyEventBase
    override type State = MyState
    override type F[A]  = cats.effect.IO[A]
  }

}
