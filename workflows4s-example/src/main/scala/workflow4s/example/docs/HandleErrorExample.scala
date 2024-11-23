package workflow4s.example.docs

import workflow4s.example.docs.Context.WIO

object HandleErrorExample {

  // start_declarative
  val doThings: WIO[MyState, MyError, Nothing]                        =
    WIO.raise[MyState](MyError()).autoNamed()
  val handleThatNastyError: WIO[(MyState, MyError), Nothing, MyState] =
    WIO.pure[(MyState, MyError)](MyState(1)).autoNamed()

  val errorHandled: WIO[MyState, Nothing, MyState] =
    doThings.handleErrorWith(handleThatNastyError)
  // end_declarative

}
