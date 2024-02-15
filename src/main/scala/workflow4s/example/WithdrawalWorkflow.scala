package workflow4s.example

import cats.effect.IO
import workflow4s.example.WithdrawalEvent.WithdrawalInitiated
import workflow4s.example.WithdrawalWorkflow.{createWithdrawalSignal, dataQuery}
import workflow4s.example.WithdrawalSignal.CreateWithdrawal
import workflow4s.wio.{SignalDef, WIO}

object WithdrawalWorkflow {
  val createWithdrawalSignal = SignalDef[CreateWithdrawal, Unit]()
  val dataQuery              = SignalDef[Unit, WithdrawalData]()
}

class WithdrawalWorkflow(service: WithdrawalService) {

  val workflow: WIO.Total[WithdrawalData.Empty.type] = for {
    _ <- handleDataQuery(
           for {
             _ <- initSignal
             _ <- calculateFees
             _ <- putMoneyOnHold
             _ <- runChecks
             _ <- execute
             _ <- releaseFunds
           } yield (),
         )
    _ <- handleDataQuery(WIO.Noop())
  } yield ()

  private def initSignal: WIO[Nothing, Unit, WithdrawalData.Empty.type, WithdrawalData.Initiated] =
    WIO
      .handleSignal[WithdrawalData.Empty.type](createWithdrawalSignal) { (_, signal) =>
        IO(WithdrawalInitiated(signal.amount))
      }
      .handleEvent { (_, event) => (WithdrawalData.Initiated(event.amount, None), ()) }

  private def calculateFees = WIO
    .runIO[WithdrawalData.Initiated](state => service.calculateFees(state.amount).map(WithdrawalEvent.FeeSet))
    .handleEvent { (state, event) => (state.copy(fee = Some(event.fee)), ()) }

  // TODO can fail with not enough funds
  private def putMoneyOnHold: WIO[Nothing, Unit, WithdrawalData.Initiated, WithdrawalData.Initiated] = WIO.Noop()

  // TODO can fail with fatal rejection or operator rejection. Need polling
  private def runChecks: WIO[Nothing, Unit, WithdrawalData.Initiated, WithdrawalData.Initiated] = WIO.Noop()

  // TODO can fail with provider fatal failure, need retries
  private def execute: WIO[Nothing, Unit, WithdrawalData.Initiated, WithdrawalData.Initiated] = WIO.Noop()

  private def releaseFunds: WIO[Nothing, Unit, WithdrawalData.Initiated, WithdrawalData.Initiated] = WIO.Noop()

  private def handleDataQuery =
    WIO.handleQuery[WithdrawalData](dataQuery) { (state, _) => state }

}
