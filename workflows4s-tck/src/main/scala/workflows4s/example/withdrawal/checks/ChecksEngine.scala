package workflows4s.example.withdrawal.checks

import com.typesafe.scalalogging.StrictLogging
import workflows4s.runtime.instanceengine.Effect
import workflows4s.runtime.instanceengine.Effect.*
import workflows4s.wio.{SignalDef, WorkflowContext}

import scala.concurrent.duration.DurationInt

class ChecksEngine[F[_], Ctx <: WorkflowContext { type Eff[A] = F[A]; type Event = ChecksEvent; type State = ChecksState }](
    ctx: Ctx,
)(using effect: Effect[F])
    extends StrictLogging {

  import ctx.WIO

  def runChecks: WIO[ChecksInput[F], Nothing, ChecksState.Decided] =
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

  private def refreshChecksUntilAllComplete: WIO[ChecksInput[F], Nothing, ChecksState.Executed] = {

    val initialize: WIO[ChecksInput[F], Nothing, ChecksState.Pending] =
      WIO.pure.makeFrom[ChecksInput[F]].value(ci => ChecksState.Pending(ci, Map())).done

    val awaitRetry: WIO[ChecksState.Pending, Nothing, ChecksState.Pending] = WIO
      .await[ChecksState.Pending](ChecksEngine.retryBackoff)
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
      .runIO[ChecksState.Pending] { state =>
        val input   = state.input.asInstanceOf[ChecksInput[F]]
        val pending = state.pendingChecks
        val checks  = input.checks.view.filterKeys(pending.contains).values.toList

        for {
          results <- effect.traverse(checks) { check =>
                       check.run(input.data).map(result => (check.key, result)).handleErrorWith { _ =>
                         logger.error("Error when running a check, falling back to manual review.")
                         (check.key, CheckResult.RequiresReview()).pure[F]
                       }
                     }
        } yield ChecksEvent.ChecksRun(results.toMap)
      }
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
    .handleSignal(ChecksEngine.signals)
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
      .throughTimeout(ChecksEngine.timeoutThreshold)
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

object ChecksEngine {
  val retryBackoff     = 20.seconds
  val timeoutThreshold = 2.minutes

  object Signals {
    val review: SignalDef[ReviewDecision, Unit] = SignalDef()
  }

  val signals: SignalDef[ReviewDecision, Unit] = Signals.review
}
