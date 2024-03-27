package workflow4s.example.testuitls

import workflow4s.wio.simple.SimpleActor

object TestUtils {
  implicit class SimpleSignalResponseOps[Resp](value: SimpleActor.SignalResponse[Resp]) {
    def extract: Resp = value match {
      case SimpleActor.SignalResponse.Ok(result)             => result
      case SimpleActor.SignalResponse.UnexpectedSignal(desc) => throw new IllegalArgumentException(s"Unexpected signal: $desc")
    }
  }
  implicit class SimpleQueryResponseOps[Resp](value: SimpleActor.QueryResponse[Resp])   {
    def extract: Resp = value match {
      case SimpleActor.QueryResponse.Ok(result)            => result
      case SimpleActor.QueryResponse.UnexpectedQuery(desc) => throw new IllegalArgumentException(s"Unexpected query: $desc")
    }
  }
}
