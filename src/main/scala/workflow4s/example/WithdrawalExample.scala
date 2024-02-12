package workflow4s.example

import cats.effect.IO
import workflow4s.example.WithdrawalExample.WithdrawalEvent.WithdrawalInitiated
import workflow4s.wio.{JournalWrite, SignalDef, WIO}

object WithdrawalExample {

  sealed trait WithdrawalData

  object WithdrawalData {
    case object Empty                        extends WithdrawalData
    case class Initiated(amount: BigDecimal) extends WithdrawalData
  }

  case class CreateWithdrawal(amount: BigDecimal)

  val createWithdrawalSignal = SignalDef[CreateWithdrawal, Unit]()
  val dataQueryDef           = SignalDef[Unit, WithdrawalData]()

  sealed trait WithdrawalEvent
  object WithdrawalEvent {
    case class WithdrawalInitiated(amount: BigDecimal)
    implicit val WithdrawalInitiatedJournalWrite: JournalWrite[WithdrawalInitiated] = null
  }

  val workflow: WIO.Total[WithdrawalData] = WIO.par(
    initSignal,
    dataQuery,
  )

  private def initSignal =
    WIO
      .handleSignal[WithdrawalData](createWithdrawalSignal) { (_, signal) =>
        IO(WithdrawalInitiated(signal.amount))
      }
      .handleEvent { (_, event) => (WithdrawalData.Initiated(event.amount), ()) }

  private def dataQuery =
    WIO.handleQuery[WithdrawalData](dataQueryDef) { (state, _) => state }

}
