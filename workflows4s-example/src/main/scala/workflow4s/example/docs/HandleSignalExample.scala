package workflow4s.example.docs

import cats.effect.IO
import workflow4s.example.docs.`package`.Context.WIO
import workflow4s.wio.SignalDef

object HandleSignalExample {

  // start_withoutError
  val MySignal = SignalDef[MyRequest, MyResponse]()

  val doThings: WIO[MyState, Nothing, MyState] =
    WIO
      .handleSignal(MySignal)
      .using[MyState]
      .withSideEffects((state, request) => IO(MyEvent()))
      .handleEvent((state, event) => state)
      .produceResponse((state, event) => MyResponse())
      .autoNamed()
  // end_withoutError

}
