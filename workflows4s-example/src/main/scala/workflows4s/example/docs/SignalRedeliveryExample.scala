package workflows4s.example.docs

import cats.effect.IO
import workflows4s.example.docs.Context.WIO
import workflows4s.wio.SignalDef

import scala.annotation.nowarn

@nowarn("msg=unused explicit parameter")
object SignalRedeliveryExample {

  val MySignal = SignalDef[MyRequest, SuccessResponse | RedeliveryMismatchResponse]()

  def processRequest(request: MyRequest): IO[String] = IO.pure(s"processed-${request.id}")

  // start_redelivery_validation
  val handleWithRedeliveryValidation: WIO[MyState, Nothing, MyState] =
    WIO
      .handleSignal(MySignal)
      .using[MyState]
      .withSideEffects((state, request) => processRequest(request).map(r => MySignalEvent(request.id, r)))
      .handleEvent((state, event) => state.copy(result = Some(event.result)))
      .produceResponse { (state, event, currentRequest) =>
        // Compare original request (stored in event) with current request
        if (event.originalRequestId != currentRequest.id) {
          // Handle mismatch - could return error response or original result
          RedeliveryMismatchResponse(expected = event.originalRequestId, received = currentRequest.id)
        } else {
          SuccessResponse(event.result)
        }
      }
      .done
  // end_redelivery_validation

}
