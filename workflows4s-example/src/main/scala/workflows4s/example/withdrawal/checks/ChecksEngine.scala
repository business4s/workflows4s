package workflows4s.example.withdrawal.checks

import cats.syntax.all.*
import com.typesafe.scalalogging.StrictLogging
import workflows4s.cats.IOWorkflowContext
import workflows4s.cats.CatsEffect.given
import workflows4s.wio.SignalDef

import scala.concurrent.duration.DurationInt

trait ChecksEngine {
  def runChecks: ChecksEngine.Context.WIO[ChecksInput, Nothing, ChecksState.Decided]
}

object ChecksEngine extends ChecksEngine with StrictLogging {
  val retryBackoff     = 20.seconds
  val timeoutThreshold = 2.minutes

  type Context = Context.Ctx
  object Context extends IOWorkflowContext {
    override type Event = ChecksEvent
    override type State = ChecksState
  }
  object Signals {
    val review: SignalDef[ReviewDecision, Unit] = SignalDef()
  }

  import Context.WIO

  def runChecks: WIO[ChecksInput, Nothing, ChecksState.Decided] =
    (refreshChecksUntilAllComplete >>> getDecision)
      .checkpointed(
        (_, state) => ChecksEvent.CheckCompleted(state.results, state.decision),
        (_, evt) => ChecksState.Decided(evt.results, evt.decision),
      )

  private def getDecision: WIO[ChecksState.Executed, Nothing, ChecksState.Decided] = {
    WIO
      .fork[ChecksState.Executed]
      .matchCondition(_.requiresReview, "Requires review?")(
        onTrue = handleReview,
        onFalse = systemDecision,
      )
  }

  private def refreshChecksUntilAllComplete: WIO[ChecksInput, Nothing, ChecksState.Executed] = {

    val initialize: WIO[ChecksInput, Nothing, ChecksState.Pending] =
      WIO.pure.makeFrom[ChecksInput].value(ci => ChecksState.Pending(ci, Map())).done

    val awaitRetry: WIO[ChecksState.Pending, Nothing, ChecksState.Pending] = WIO
      .await[ChecksState.Pending](retryBackoff)
      .persistStartThrough(started => ChecksEvent.AwaitingRefresh(started.at))(_.started)
      .persistReleaseThrough(released => ChecksEvent.RefreshReleased(released.at))(_.released)
      .autoNamed

    def isDone(checksState: ChecksState.Pending): Option[ChecksState.Executed] = checksState.asExecuted

    val iterateUntilDone: WIO[ChecksState.Pending, Nothing, ChecksState.Executed] = WIO
      .repeat(runPendingChecks)
      .untilSome(isDone)
      .onRestart(awaitRetry)
      .named(conditionName = "All checks completed?", releaseBranchName = "Yes", restartBranchName = "No")

    initialize >>> iterateUntilDone.interruptWith(executionTimeout)
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
              .handleError(_ => {
                logger.error("Error when running a check, falling back to manual review.")
                CheckResult.RequiresReview()
              })
              .tupleLeft(check.key),
          )
          .map(results => ChecksEvent.ChecksRun(results.toMap))
      })
      .handleEvent((state, evt) => state.addResults(evt.results))
      .autoNamed()

  private val systemDecision: WIO[ChecksState.Executed, Nothing, ChecksState.Decided] =
    WIO.pure
      .makeFrom[ChecksState.Executed]
      .value(st => {
        val decision =
          if st.isRejected then Decision.RejectedBySystem()
          else Decision.ApprovedBySystem()
        st.asDecided(decision)
      })
      .autoNamed

  private def handleReview: WIO[ChecksState.Executed, Nothing, ChecksState.Decided] = WIO
    .handleSignal(Signals.review)
    .using[ChecksState.Executed]
    .purely((_, sig) => ChecksEvent.ReviewDecisionTaken(sig))
    .handleEvent({ case (st, evt) =>
      val decision = evt.decision match {
        case ReviewDecision.Approve => Decision.ApprovedByOperator()
        case ReviewDecision.Reject  => Decision.RejectedByOperator()
      }
      st.asDecided(decision)
    })
    .voidResponse
    .done

  private def executionTimeout: WIO.Interruption[Nothing, ChecksState.Executed] =
    WIO.interruption
      .throughTimeout(timeoutThreshold)
      .persistStartThrough(started => ChecksEvent.AwaitingTimeout(started.at))(_.started)
      .persistReleaseThrough(released => ChecksEvent.ExecutionTimedOut(released.at))(_.releasedAt)
      .autoNamed
      .andThen(_ >>> putInReview)

  private def putInReview: WIO[ChecksState, Nothing, ChecksState.Executed] =
    WIO.pure
      .makeFrom[ChecksState]
      .value({
        case progress: ChecksState.InProgress =>
          ChecksState.Executed(progress.results.map({
            case (key, result: CheckResult.Finished) => (key, result)
            case (key, _)                            => (key, CheckResult.TimedOut())
          }))
        case _: ChecksState.Decided           => ??? // not supported
      })
      .autoNamed

}
