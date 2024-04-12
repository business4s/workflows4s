package workflow4s.example.docs

import cats.effect.IO
import cats.implicits.catsSyntaxEitherId
import workflow4s.example.docs
import workflow4s.example.docs.Context.WIO
import workflow4s.wio

object HandleErrorExample {

  // start_declarative
  val doThings: WIO[MyState, MyError, Nothing]               = WIO.raise[MyState](MyError()).autoNamed()
  val handleError: WIO[(MyState, MyError), Nothing, MyState] = WIO.pure[(MyState, MyError)](MyState(1)).autoNamed()

  val errorHandled = doThings.handleErrorWith(handleError)
  // end_declarative

}
