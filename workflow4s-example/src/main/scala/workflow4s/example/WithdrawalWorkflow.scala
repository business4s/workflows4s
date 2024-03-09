package workflow4s.example

import cats.effect.IO
import cats.implicits.catsSyntaxEitherId
import workflow4s.example.WithdrawalEvent.{MoneyLocked, WithdrawalInitiated}
import workflow4s.example.WithdrawalService.ExecutionResponse
import workflow4s.example.WithdrawalSignal.{CreateWithdrawal, ExecutionCompleted}
import workflow4s.example.WithdrawalWorkflow.{createWithdrawalSignal, dataQuery, executionCompletedSignal}
import workflow4s.example.checks.{ChecksEngine, ChecksInput, ChecksState, Decision}
import workflow4s.wio.{SignalDef, WIO}

import scala.reflect.runtime.universe.typeOf

object WithdrawalWorkflow {
  val createWithdrawalSignal   = SignalDef[CreateWithdrawal, Unit]()
  val dataQuery                = SignalDef[Unit, WithdrawalData]()
  val executionCompletedSignal = SignalDef[ExecutionCompleted, Unit]()
}

class WithdrawalWorkflow(service: WithdrawalService, checksEngine: ChecksEngine) {

  val workflow: WIO[WithdrawalRejection, Unit, WithdrawalData.Empty, Nothing] = for {
    _ <- handleDataQuery(
           (for {
             _ <- receiveWithdrawal
             _ <- calculateFees
             _ <- putMoneyOnHold
             _ <- runChecks
             _ <- execute
             _ <- releaseFunds
           } yield ())
             .handleError(handleRejection),
         )
    _ <- handleDataQuery(WIO.Noop())
  } yield ()

  val workflowDeclarative: WIO[WithdrawalRejection, Unit, WithdrawalData.Empty, Nothing] =
    handleDataQuery(
      (
        receiveWithdrawal >>>
          calculateFees >>>
          putMoneyOnHold >>>
          runChecks >>>
          execute >>>
          releaseFunds
      ).handleError(handleRejection) >>> WIO.Noop(),
    )

  private def receiveWithdrawal: WIO[Nothing, Unit, WithdrawalData.Empty, WithdrawalData.Initiated] =
    WIO
      .handleSignal[WithdrawalData.Empty](createWithdrawalSignal) { (_, signal) =>
        // TODO validate amount to be positive (show rejected signal)
        IO(WithdrawalInitiated(signal.amount, signal.recipient))
      }
      .handleEvent { (st, event) => (st.initiated(event.amount, event.recipient), ()) }
      .produceResponse((_, _) => ())
      .autoNamed()

  private def calculateFees: WIO[Nothing, Unit, WithdrawalData.Initiated, WithdrawalData.Validated] = WIO
    .runIO[WithdrawalData.Initiated](state => service.calculateFees(state.amount).map(WithdrawalEvent.FeeSet))
    .handleEvent { (state, event) => (state.validated(event.fee), ()) }
    .autoNamed()

  private def putMoneyOnHold: WIO[WithdrawalRejection.NotEnoughFunds, Unit, WithdrawalData.Validated, WithdrawalData.Validated] =
    WIO
      .runIO[WithdrawalData.Validated](state =>
        service
          .putMoneyOnHold(state.amount)
          .map({
            case Left(WithdrawalService.NotEnoughFunds()) => WithdrawalEvent.MoneyLocked.NotEnoughFunds()
            case Right(_)                                 => WithdrawalEvent.MoneyLocked.Success()
          }),
      )
      .handleEventWithError { (state, evt) =>
        evt match {
          case MoneyLocked.Success()        => (state, ()).asRight
          case MoneyLocked.NotEnoughFunds() => WithdrawalRejection.NotEnoughFunds().asLeft
        }
      }
      .autoNamed()

  private def runChecks: WIO[WithdrawalRejection.RejectedInChecks, Unit, WithdrawalData.Validated, WithdrawalData.Checked]       =
    (for {
      state    <- WIO.getState[WithdrawalData.Validated]
      decision <- checksEngine
                    .runChecks(ChecksInput(state, List()))
                    .transformState[WithdrawalData.Validated, WithdrawalData.Checked](
                      _ => ChecksState.empty,
                      (validated, checkState) => validated.checked(checkState),
                    )
      _        <- decision match {
                    case Decision.RejectedBySystem()   => WIO.raise[WithdrawalData.Checked](WithdrawalRejection.RejectedInChecks(state.txId))
                    case Decision.ApprovedBySystem()   => WIO.unit[WithdrawalData.Checked]
                    case Decision.RejectedByOperator() => WIO.raise[WithdrawalData.Checked](WithdrawalRejection.RejectedInChecks(state.txId))
                    case Decision.ApprovedByOperator() => WIO.unit[WithdrawalData.Checked]
                  }
    } yield ()).autoNamed()

  // TODO can fail with provider fatal failure, need retries, needs cancellation
  private def execute: WIO[WithdrawalRejection.RejectedByExecutionEngine, Unit, WithdrawalData.Checked, WithdrawalData.Executed] =
    initiateExecution >>> awaitExecutionCompletion

  private def initiateExecution: WIO[WithdrawalRejection.RejectedByExecutionEngine, Unit, WithdrawalData.Checked, WithdrawalData.Executed] =
    WIO
      .runIO[WithdrawalData.Checked](s =>
        service
          .initiateExecution(s.netAmount, s.recipient)
          .map(WithdrawalEvent.ExecutionInitiated),
      )
      .handleEventWithError((s, event) =>
        event.response match {
          case ExecutionResponse.Accepted(externalId) => Right(s.executed(externalId) -> ())
          case ExecutionResponse.Rejected(error)      => Left(WithdrawalRejection.RejectedByExecutionEngine(s.txId, error))
        },
      )
      .autoNamed()

  private def awaitExecutionCompletion: WIO[WithdrawalRejection.RejectedByExecutionEngine, Unit, WithdrawalData.Executed, WithdrawalData.Executed] =
    WIO
      .handleSignal[WithdrawalData.Executed](executionCompletedSignal)((state, sig) => IO(WithdrawalEvent.ExecutionCompleted(sig)))
      .handleEventWithError((s, e: WithdrawalEvent.ExecutionCompleted) =>
        e.status match {
          case ExecutionCompleted.Succeeded => Right(s, ())
          case ExecutionCompleted.Failed    => Left(WithdrawalRejection.RejectedByExecutionEngine(s.txId, "Execution failed"))
        },
      )
      .produceResponse((_, _) => ())
      .autoNamed()

  private def releaseFunds: WIO[Nothing, Unit, WithdrawalData.Executed, WithdrawalData.Completed] =
    WIO
      .runIO[WithdrawalData.Executed](st => service.putMoneyOnHold(st.amount).as(WithdrawalEvent.MoneyReleased()))
      .handleEvent((st, e) => st.completed() -> ())
      .autoNamed()

  private def handleDataQuery =
    WIO.handleQuery[WithdrawalData](dataQuery) { (state, _) => state }

  private def handleRejection(r: WithdrawalRejection): WIO[Nothing, Unit, Any, WithdrawalData.Completed] =
    (r match {
      case WithdrawalRejection.NotEnoughFunds()                       => WIO.setState(WithdrawalData.Completed())
      case WithdrawalRejection.RejectedInChecks(txId)                 => cancelFunds(txId)
      case WithdrawalRejection.RejectedByExecutionEngine(txId, error) => cancelFunds(txId)
    }

  private def cancelFunds(txId: String): WIO[Nothing, Unit, Any, WithdrawalData.Completed] = WIO.Noop()
}
