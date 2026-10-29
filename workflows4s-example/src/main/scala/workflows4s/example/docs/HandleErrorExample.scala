package workflows4s.example.docs

import workflows4s.example.docs.Context.*

object HandleErrorExample {

  // start_declarative
  val doThings: WIO[MyState, MyError, Nothing]                        =
    WIO.pure.error(MyError()).autoNamed()
  val handleThatNastyError: WIO[(MyState, MyError), Nothing, MyState] =
    WIO.pure(MyState(1)).autoNamed()

  val errorHandled: WIO[MyState, Nothing, MyState] =
    doThings.handleErrorWith(handleThatNastyError)
  // end_declarative

  // start_dynamic
  val incrementCounter: WIO[MyState, Nothing, MyState]         = WIO.pure.makeFrom[MyState].value(s => s.copy(counter = s.counter + 1)).autoNamed()
  val resetCounter: WIO[MyState, Nothing, MyState]             = WIO.pure.makeFrom[MyState].value(_.copy(counter = 0)).autoNamed()
  val chooseHandler: WIO[(MyState, MyError), Nothing, MyState] =
    WIO.pure
      .makeFrom[(MyState, MyError)]
      .value((state, error) => state.copy(result = Some(error.toString)))
      .autoNamed()
      .flatMap(state => if state.counter < 3 then incrementCounter else resetCounter)

  val errorHandledDynamically: WIO[MyState, Nothing, MyState] =
    doThings.handleErrorWith(chooseHandler)
  // end_dynamic

}
