package workflow4s.example.checks

import cats.effect.IO
import cats.syntax.all._
import workflow4s.wio.{SignalDef, WIO}

trait ChecksEngine {
  def runChecks: WIO[Nothing, Unit, ChecksInput, ChecksState.Decided]
}

object ChecksEngine extends ChecksEngine {

  val reviewSignalDef: SignalDef[ReviewDecision, Unit] = SignalDef()

  def runChecks: WIO[Nothing, Unit, ChecksInput, ChecksState.Decided] =
    refreshChecksUntilAllComplete >>> getDecision()
  //      .checkpointed((s, decision) => ChecksEvent.CheckCompleted(s.results, decision))((s, e) => (s, e.decision))

  private def getDecision(): WIO[Nothing, Unit, ChecksState.Executed, ChecksState.Decided] = {
    WIO
      .fork[ChecksState.Executed]
      .addBranch(requiresReviewBranch)
      .addBranch(decidedBySystemBranch) // TODO if/else api
      .done
  }

  private def refreshChecksUntilAllComplete: WIO[Nothing, Unit, ChecksInput, ChecksState.Executed] = {

    def initialize: WIO[Nothing, Unit, ChecksInput, ChecksState.Pending] =
      WIO.pure[ChecksInput].makeState(ci => ChecksState.Pending(ci, Map()))

    def isDone(checksState: ChecksState.Pending): Option[ChecksState.Executed] = checksState.asExecuted

    initialize >>> WIO
      .repeat(runPendingChecks)
      .untilSome(isDone)
  }

  private def runPendingChecks: WIO[Nothing, Unit, ChecksState.Pending, ChecksState.Pending] =
    WIO
      .runIO[ChecksState.Pending](state => {
        val pending = state.pendingChecks
        val checks  = state.input.checks.view.filterKeys(pending.contains).values.toList
        checks
          .traverse(check =>
            check
              .run(state.input.data)
              .handleError(_ => CheckResult.RequiresReview()) // TODO logging
              .tupleLeft(check.key),
          )
          .map(results => ChecksEvent.ChecksRun(results.toMap))
      })
      .handleEvent((state, evt) => (state.addResults(evt.results), ()))
      .autoNamed()

  private val decidedBySystemBranch: WIO.Branch[Nothing, Unit, ChecksState.Executed, ChecksState.Decided] =
    WIO
      .branch[ChecksState.Executed]
      .when(!_.requiresReview)(
        WIO.pure.makeState(st => {
          val decision =
            if (st.isRejected) Decision.RejectedBySystem()
            else Decision.ApprovedBySystem()
          st.asDecided(decision)
        }),
      )

  private val requiresReviewBranch: WIO.Branch[Nothing, Unit, ChecksState.Executed, ChecksState.Decided] =
    WIO.branch[ChecksState.Executed].when(_.requiresReview)(handleReview)

  private def handleReview: WIO[Nothing, Unit, ChecksState.Executed, ChecksState.Decided] = WIO
    .handleSignal[ChecksState.Executed](reviewSignalDef)({ case (_, sig) => IO(ChecksEvent.ReviewDecisionTaken(sig)) })
    .handleEvent({ case (st, evt) =>
      val decision = evt.decision match {
        case ReviewDecision.Approve => Decision.ApprovedByOperator()
        case ReviewDecision.Reject  => Decision.RejectedByOperator()
      }
      (st.asDecided(decision), ())
    })
    .produceResponse((_, _) => ())

  //  def handleInterruption

}
