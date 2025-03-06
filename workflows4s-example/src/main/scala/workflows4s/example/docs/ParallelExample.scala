package workflows4s.example.docs

import workflows4s.example.docs.Context.WIO

object ParallelExample {

  // start_doc
  val doA = WIO.pure(MyState(1)).autoNamed
  val doB = WIO.pure(MyState(2)).autoNamed

  val parallel: WIO[Int, Nothing, MyState] =
    WIO.parallel
      .taking[Int]
      .withInterimState(initial => MyState(initial))
      .withElement(
        logic = doA,
        incorporatedWith = (interimState, pathState) => MyState(interimState.counter + pathState.counter),
      )
      .withElement(
        logic = doB,
        incorporatedWith = (interimState, pathState) => MyState(interimState.counter - pathState.counter),
      )
      .producingOutputWith((aOut, bOut) => MyState(aOut.counter * bOut.counter))
  // end_doc

}
