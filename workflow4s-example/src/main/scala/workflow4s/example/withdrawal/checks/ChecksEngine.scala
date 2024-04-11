package workflow4s.example.withdrawal.checks

import cats.effect.IO
import cats.syntax.all.*
import workflow4s.wio.{SignalDef, WorkflowContext}

trait ChecksEngine {
  def runChecks: ChecksEngine.Context.WIO[ChecksInput, Nothing, ChecksState.Decided]
}

object ChecksEngine extends ChecksEngine {

  object Context extends WorkflowContext {
    override type Event = ChecksEvent
    override type State = ChecksState
  }

  val reviewSignalDef: SignalDef[ReviewDecision, Unit] = SignalDef()
  import Context.WIO

  def runChecks: WIO[ChecksInput, Nothing, ChecksState.Decided] =
    refreshChecksUntilAllComplete >>> getDecision()
  //      .checkpointed((s, decision) => ChecksEvent.CheckCompleted(s.results, decision))((s, e) => (s, e.decision))

  private def getDecision(): WIO[ChecksState.Executed, Nothing, ChecksState.Decided] = {
    WIO
      .fork[ChecksState.Executed]
      .addBranch(requiresReviewBranch)
      .addBranch(decidedBySystemBranch) // TODO if/else api
      .done
  }

  private def refreshChecksUntilAllComplete: WIO[ChecksInput, Nothing, ChecksState.Executed] = {

    def initialize: WIO[ChecksInput, Nothing , ChecksState.Pending] =
      WIO.pure[ChecksInput].make(ci => ChecksState.Pending(ci, Map()))

    def isDone(checksState: ChecksState.Pending): Option[ChecksState.Executed] = checksState.asExecuted

    initialize >>> WIO
      .repeat(runPendingChecks)
      .untilSome(isDone)
  }

  private def runPendingChecks: WIO[ChecksState.Pending, Nothing, ChecksState.Pending] =
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
      .handleEvent((state, evt) => state.addResults(evt.results))
      .autoNamed()

  private val decidedBySystemBranch: WIO.Branch[ChecksState.Executed, Nothing, ChecksState.Decided] =
    WIO
      .branch[ChecksState.Executed]
      .when(!_.requiresReview)(
        WIO.pure.make(st => {
          val decision =
            if (st.isRejected) Decision.RejectedBySystem()
            else Decision.ApprovedBySystem()
          st.asDecided(decision)
        }),
      )

  private val requiresReviewBranch: WIO.Branch[ChecksState.Executed, Nothing, ChecksState.Decided] =
    WIO.branch[ChecksState.Executed].when(_.requiresReview)(handleReview)

  private def handleReview: WIO[ChecksState.Executed, Nothing, ChecksState.Decided] = WIO
    .handleSignal[ChecksState.Executed](reviewSignalDef)({ case (_, sig) => IO(ChecksEvent.ReviewDecisionTaken(sig)) })
    .handleEvent({ case (st, evt) =>
      val decision = evt.decision match {
        case ReviewDecision.Approve => Decision.ApprovedByOperator()
        case ReviewDecision.Reject  => Decision.RejectedByOperator()
      }
      st.asDecided(decision)
    })
    .produceResponse((_, _) => ())

  //  def handleInterruption

}
