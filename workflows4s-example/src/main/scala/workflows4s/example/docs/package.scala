package workflows4s.example

import workflows4s.wio.WorkflowContext

import java.time.Instant

package object docs {

  sealed trait MyEventBase
  case class MyEvent()                                                extends MyEventBase
  case class MyTimerStarted(time: Instant)                            extends MyEventBase
  case class MyTimerReleased(time: Instant)                           extends MyEventBase
  case object MyRetryEvent                                            extends MyEventBase
  case class MySignalEvent(originalRequestId: String, result: String) extends MyEventBase
  case class MyState(counter: Int, result: Option[String] = None)
  case class MyError()
  case class MyRequest(id: String = "")
  case class MyResponse()
  case class RedeliveryMismatchResponse(expected: String, received: String)
  case class SuccessResponse(result: String)

  object Context extends WorkflowContext {
    override type Event = MyEventBase
    override type State = MyState
  }

}
