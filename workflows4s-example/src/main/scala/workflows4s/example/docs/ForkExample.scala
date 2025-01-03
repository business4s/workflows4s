package workflows4s.example.docs

import workflows4s.example.docs.Context.WIO

object ForkExample {

  // start_doc
  val doA = WIO.pure(MyState(1)).autoNamed
  val doB = WIO.pure(MyState(2)).autoNamed

  val fork: WIO[MyState, Nothing, MyState] =
    WIO
      .fork[MyState]
      .matchCondition(_.counter > 0, "Is counter positive?")(
        onTrue = doA,
        onFalse = doB,
      )
  // end_doc

}
