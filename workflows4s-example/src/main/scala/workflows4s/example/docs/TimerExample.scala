package workflows4s.example.docs

import workflows4s.example.docs.Context.WIO

import scala.concurrent.duration.DurationInt

object TimerExample {

  // start_doc
  val waitForInput: WIO[MyState, Nothing, MyState] =
    WIO
      .await[MyState](1.day)
      .persistStartThrough(start => MyTimerStarted(start.at))(evt => evt.time)
      .persistReleaseThrough(release => MyTimerReleased(release.at))(evt => evt.time)
      .autoNamed
  // end_doc

}
