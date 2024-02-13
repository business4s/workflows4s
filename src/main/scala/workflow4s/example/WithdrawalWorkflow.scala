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

  val workflow: WIO.Total[WithdrawalData] = WIO.par(
    for {
      _ <- initSignal
      _ <- calculateFees
    } yield (),
    handleDataQuery,
  )

  private def initSignal: WIO[Nothing, Unit, WithdrawalData] =
    WIO
      .handleSignal[WithdrawalData](createWithdrawalSignal) { (_, signal) =>
        IO(WithdrawalInitiated(signal.amount))
      }
      .handleEvent { (_, event) => (WithdrawalData.Initiated(event.amount, None), ()) }

  private def calculateFees = WIO
    .runIO[WithdrawalData](state => service.calculateFees(state.asInstanceOf[WithdrawalData.Initiated].amount).map(WithdrawalEvent.FeeSet))
    .handleEvent { (state, event) => (state.asInstanceOf[WithdrawalData.Initiated].copy(fee = Some(event.fee)), ()) }

  private def handleDataQuery =
    WIO.handleQuery[WithdrawalData](dataQuery) { (state, _) => state }

}
