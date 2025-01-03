package workflows4s.example

import workflows4s.wio.WorkflowContext

import java.time.Instant

package object docs {

  sealed trait MyEventBase
  case class MyEvent() extends MyEventBase
  case class MyTimerStarted(time: Instant) extends MyEventBase
  case class MyTimerReleased(time: Instant) extends MyEventBase
  case class MyState(counter: Int)
  case class MyError()
  case class MyRequest()
  case class MyResponse()

  object Context extends WorkflowContext {
    override type Event = MyEventBase
    override type State = MyState
  }

}
