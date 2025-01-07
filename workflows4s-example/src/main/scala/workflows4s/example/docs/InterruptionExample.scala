package workflows4s.example.docs

import workflows4s.example.docs.Context.WIO
import workflows4s.wio.SignalDef

object InterruptionExample {

  // start_signal
  val MySignal = SignalDef[MyRequest, MyResponse]()
  val doA      = WIO.pure(MyState(1)).autoNamed
  val doB      = WIO.pure(MyState(1)).autoNamed

  val interruption =
    WIO.interruption
      .throughSignal(MySignal)
      .handleSync((state, request) => MyEvent())
      .handleEvent((state, event) => MyState(0))
      .produceResponse((state, event) => MyResponse())
      .autoNamed()
      .andThen(_ >>> doB)

  val interruptedThroughSignal = doA.interruptWith(interruption)
  // end_signal

}
