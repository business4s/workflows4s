package workflow4s.example

import cats.effect.IO
import workflow4s.example.WithdrawalExample.WithdrawalEvent.WithdrawalInitiated
import workflow4s.wio.{JournalWrite, SignalDef, WIO}

object WithdrawalExample {

  case class WithdrawalData()

  case class CreateWithdrawal(amount: BigDecimal)

  val createWithdrawalSignal = SignalDef[CreateWithdrawal, Unit]()

  sealed trait WithdrawalEvent
  object WithdrawalEvent {
    case class WithdrawalInitiated(amount: BigDecimal)
    implicit val WithdrawalInitiatedJournalWrite: JournalWrite[WithdrawalInitiated] = null
  }

  val workflow = initSignal

  private def initSignal =
    WIO
      .handleSignal[WithdrawalData](createWithdrawalSignal) { (_, signal) =>
        IO(WithdrawalInitiated(signal.amount))
      }
      .handleEvent { (state, _) => (state, ())}

}
