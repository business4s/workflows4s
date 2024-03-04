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
    (for {
      _        <- refreshChecksUntilAllComplete(input)
      decision <- getDecision()
    } yield decision).checkpointed((s, decision) => ChecksEvent.CheckCompleted(s.results, decision))((s, e) => (s, e.decision))

  private def getDecision(): WIO[Nothing, Decision, ChecksState, ChecksState] = {
    for {
      state    <- WIO.getState
      decision <- detectRejected(state)
                    .orElse(handleSignalAwaiting(state))
                    .orElse(decideThroughReview(state))
                    .getOrElse(WIO.pure(Decision.ApprovedBySystem()))
    } yield decision
  }

  private def refreshChecksUntilAllComplete(input: ChecksInput): WIO[Nothing, Unit, ChecksState, ChecksState] = {
    for {
      state         <- WIO.getState[ChecksState]
      pending        = (input.checks -- state.finished).values.toList
      _             <- doRun(pending, input.data)
      stateAfterRun <- WIO.getState[ChecksState]
      allFinished    = stateAfterRun.finished.size == input.checks.size
      _             <- if (allFinished) WIO.Noop() else refreshChecksUntilAllComplete(input)
    } yield ()
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

  private def detectRejected(state: ChecksState): Option[WIO[Nothing, Decision.RejectedBySystem, ChecksState, ChecksState]] =
    state.results.collectFirst({ case (_, CheckResult.Rejected()) =>
      WIO.pure[ChecksState](Decision.RejectedBySystem())
    })

  private def handleSignalAwaiting(state: ChecksState): Option[WIO[Nothing, Decision, ChecksState, ChecksState]] =
    state.results.collectFirst({ case (key, CheckResult.RequiresSignal(signalDef)) =>
      WIO
        .handleSignal[ChecksState](signalDef)({ case (_, _) => IO(ChecksEvent.ApproveSignalReceived(key)) })
        .handleEvent({ case (st, evt) =>
          st.addResults(Map(evt.check -> CheckResult.Approved())) -> ()
        })
        .produceResponse((_, _) => ())
        .flatMap(_ => getDecision())
    })

  private def decideThroughReview(state: ChecksState): Option[WIO[Nothing, Decision, ChecksState, ChecksState]] =
    state.results.collectFirst({ case (_, CheckResult.RequiresReview()) =>
      WIO
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
    })

//  def handleInterruption

}
