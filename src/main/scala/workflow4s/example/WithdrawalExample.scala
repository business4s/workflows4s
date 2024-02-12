package workflow4s.example

import cats.effect.IO
import workflow4s.example.WithdrawalEvent.WithdrawalInitiated
import workflow4s.example.WithdrawalSignal.CreateWithdrawal
import workflow4s.wio.{SignalDef, WIO}

object WithdrawalExample {

  val createWithdrawalSignal = SignalDef[CreateWithdrawal, Unit]()
  val dataQuery              = SignalDef[Unit, WithdrawalData]()

  val workflow: WIO.Total[WithdrawalData] = WIO.par(
    for {
      _ <- initSignal
      _ <- secondSignal
    } yield (),
    hadnleDataQuery,
  )

  private def initSignal =
    WIO
      .handleSignal[WithdrawalData](createWithdrawalSignal) { (_, signal) =>
        IO(WithdrawalInitiated(signal.amount))
      }
      .handleEvent { (_, event) => (WithdrawalData.Initiated(event.amount), ()) }


  private def secondSignal = WIO
    .handleSignal[WithdrawalData](createWithdrawalSignal) { (_, signal) =>
      IO(WithdrawalInitiated(signal.amount))
    }
    .handleEvent { (_, event) => (WithdrawalData.Initiated(event.amount * 2), ()) }

  private def hadnleDataQuery =
    WIO.handleQuery[WithdrawalData](dataQuery) { (state, _) => state }

}
