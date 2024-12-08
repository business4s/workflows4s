package workflow4s.example.docs

import cats.effect.IO
import cats.implicits.catsSyntaxEitherId
import workflow4s.example.docs.Context.WIO

object RunIOExample {

  // start_withoutError
  val doThings: WIO[MyState, Nothing, MyState] =
    WIO
      .runIO[MyState](state => IO(MyEvent()))
      .handleEvent((state, event) => MyState(state.counter + 1))
      .autoNamed
  // end_withoutError

  // start_withError
  val doThingsWithError: WIO[MyState, MyError, MyState] =
    WIO
      .runIO[MyState](state => IO(MyEvent()))
      .handleEventWithError((state, event) =>
        if (true) MyState(state.counter + 1).asRight
        else MyError().asLeft,
      )
      .autoNamed
  // end_withError

}
