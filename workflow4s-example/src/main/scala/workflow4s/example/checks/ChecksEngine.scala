package workflow4s.example.checks

import cats.effect.IO
import cats.syntax.all._
import workflow4s.wio.{SignalDef, WIO}

trait ChecksEngine {
  def runChecks(input: ChecksInput): WIO[Nothing, Decision, ChecksState, ChecksState]
}

object ChecksEngine extends ChecksEngine {

  val reviewSignalDef: SignalDef[ReviewDecision, Unit] = SignalDef()

  def runChecks(input: ChecksInput): WIO[Nothing, Decision, ChecksState, ChecksState] =
    refreshChecksUntilAllComplete(input) >>> getDecision()
  //      .checkpointed((s, decision) => ChecksEvent.CheckCompleted(s.results, decision))((s, e) => (s, e.decision))

  private def getDecision(): WIO[Nothing, Decision, ChecksState, ChecksState] = {
    WIO
      .fork[ChecksState]
      .addBranch(requiresReviewBranch)
      .addBranch(decidedBySystemBranch) // TODO if/else api
      .done
  }

  private def refreshChecksUntilAllComplete(input: ChecksInput): WIO[Nothing, Unit, ChecksState, ChecksState] = {
    val refreshChecks = (for {
      state  <- WIO.getState[ChecksState]
      pending = (input.checks -- state.finished).values.toList
      _      <- doRun(pending, input.data)
    } yield ()).autoNamed()

    WIO.doWhile(refreshChecks)((state, _) => {
      val allFinished = state.finished.size == input.checks.size
      allFinished
    })
  }

  private def doRun[Data](checks: List[Check[Data]], data: Data): WIO[Nothing, Unit, ChecksState, ChecksState] =
    WIO
      .runIO[ChecksState](_ =>
        checks
          .traverse(check =>
            check
              .run(data)
              .handleError(_ => CheckResult.RequiresReview()) // TODO logging
              .tupleLeft(check.key),
          )
          .map(results => ChecksEvent.ChecksRun(results.toMap)),
      )
      .handleEvent((state, evt) => (state.addResults(evt.results), ()))

  private def decidedBySystemBranch =
    WIO
      .branch[ChecksState]
      .when(_.requiresReview)(
        WIO.pure.make(st =>
          if (st.isRejected) Decision.RejectedBySystem()
          else Decision.ApprovedBySystem(),
        ),
      )

  private def requiresReviewBranch =
    WIO.branch[ChecksState].when(_.requiresReview)(handleReview)

  private def handleReview: WIO[Nothing, Decision, ChecksState, ChecksState] = WIO
    .handleSignal[ChecksState](reviewSignalDef)({ case (_, sig) => IO(ChecksEvent.ReviewDecisionTaken(sig)) })
    .handleEvent({ case (st, evt) =>
      (
        st,
        evt.decision match {
          case ReviewDecision.Approve => Decision.ApprovedByOperator()
          case ReviewDecision.Reject  => Decision.RejectedByOperator()
        },
      )
    })
    .produceResponse((_, _) => ())

  //  def handleInterruption

}
