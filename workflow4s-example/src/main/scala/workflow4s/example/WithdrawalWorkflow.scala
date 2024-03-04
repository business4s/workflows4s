package workflow4s.example

import cats.effect.IO
import cats.implicits.catsSyntaxEitherId
import workflow4s.example.WithdrawalEvent.{MoneyLocked, WithdrawalInitiated}
import workflow4s.example.WithdrawalSignal.CreateWithdrawal
import workflow4s.example.WithdrawalWorkflow.{createWithdrawalSignal, dataQuery}
import workflow4s.example.checks.{ChecksEngine, ChecksInput, ChecksState, Decision}
import workflow4s.wio.{SignalDef, WIO}

object WithdrawalWorkflow {
  val createWithdrawalSignal = SignalDef[CreateWithdrawal, Unit]()
  val dataQuery              = SignalDef[Unit, WithdrawalData]()
}

class WithdrawalWorkflow(service: WithdrawalService) {

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

  private def receiveWithdrawal: WIO[Nothing, Unit, WithdrawalData.Empty, WithdrawalData.Initiated] =
    WIO
      .handleSignal[WithdrawalData.Empty](createWithdrawalSignal) { (_, signal) =>
        IO(WithdrawalInitiated(signal.amount))
      }
      .handleEvent { (st, event) => (st.initiated(event.amount), ()) }
      .produceResponse((_, _) => ())
      .autoNamed()

  private def calculateFees: WIO[Nothing, Unit, WithdrawalData.Initiated, WithdrawalData.Validated] = WIO
    .runIO[WithdrawalData.Initiated](state => service.calculateFees(state.amount).map(WithdrawalEvent.FeeSet))
    .handleEvent { (state, event) => (state.validated(event.fee), ()) }

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

  private def runChecks: WIO[WithdrawalRejection.RejectedInChecks, Unit, WithdrawalData.Validated, WithdrawalData.Checked] =
    for {
      state    <- WIO.getState
      decision <- ChecksEngine
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
    } yield ()

  // TODO can fail with provider fatal failure, need retries
  private def execute: WIO[WithdrawalRejection.RejectedByExecutionEngine, Unit, WithdrawalData.Checked, WithdrawalData.Executed] = WIO.Noop()

  private def releaseFunds: WIO[Nothing, Unit, WithdrawalData.Executed, WithdrawalData.Completed] = WIO.Noop()

  private def handleDataQuery =
    WIO.handleQuery[WithdrawalData](dataQuery) { (state, _) => state }

  private def handleRejection(r: WithdrawalRejection): WIO[Nothing, Unit, Any, WithdrawalData.Completed] =
    r match {
      case WithdrawalRejection.NotEnoughFunds()                => WIO.setState(WithdrawalData.Completed())
      case WithdrawalRejection.RejectedInChecks(txId)          => cancelFunds(txId)
      case WithdrawalRejection.RejectedByExecutionEngine(txId) => cancelFunds(txId)
    }

  private def cancelFunds(txId: String): WIO[Nothing, Unit, Any, WithdrawalData.Completed] = WIO.Noop()
}
