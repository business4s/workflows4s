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
  val retryHandler: WIO[MyState, Nothing, MyState]             = WIO.pure.makeFrom[MyState].value(s => s.copy(counter = s.counter + 1)).autoNamed()
  val giveUpHandler: WIO[MyState, Nothing, MyState]            = WIO.pure.makeFrom[MyState].value(_.copy(result = Some("failed"))).autoNamed()
  val chooseHandler: WIO[(MyState, MyError), Nothing, MyState] =
    WIO.pure
      .makeFrom[(MyState, MyError)]
      .value((state, _) => state)
      .autoNamed()
      .flatMap(state => if state.counter < 3 then retryHandler else giveUpHandler)

  val errorHandledDynamically: WIO[MyState, Nothing, MyState] =
    doThings.handleErrorWith(chooseHandler)
  // end_dynamic

}
