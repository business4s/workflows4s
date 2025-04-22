package workflows4s.example.docs

import workflows4s.example.docs.Context.WIO

object PureExample {

  // start_withoutError
  val doThings: WIO[Any, Nothing, MyState] =
    WIO.pure(MyState(1)).autoNamed

  val doThings2: WIO[MyState, Nothing, MyState] =
    WIO.pure
      .makeFrom[MyState]
      .value(state => MyState(state.counter))
      .autoNamed
  // end_withoutError

  // start_withError
  val doThingsWithError: WIO[Any, MyError, Nothing] =
    WIO.pure.error(MyError()).autoNamed

  val doThingsWithError2: WIO[MyState, MyError, Nothing] =
    WIO.pure
      .makeFrom[MyState]
      .error(_ => MyError())
      .autoNamed
  // end_withError

}
