package workflows4s.example.docs

import workflows4s.example.docs.Context.WIO

object LoopExample {

  // start_loop
  val step1 = WIO.pure(MyState(1)).autoNamed
  val step2 = WIO.pure(MyState(1)).autoNamed

  val loop: WIO[MyState, Nothing, MyState] = WIO
    .repeat(step1)
    .untilSome(state => Some(state))
    .onRestart(step2)
    .named(
      conditionName = "Is everything done?",
      releaseBranchName = "Yes!",
      restartBranchName = "No",
    )
  // end_loop

  object Simple {
    // start_simple
    val step1 = WIO.pure(MyState(1)).autoNamed

    val loop: WIO[MyState, Nothing, MyState] = WIO
      .repeat(step1)
      .until(state => state.counter > 0)
      .onRestartDoNothing
      .done
    // end_simple

  }

}
