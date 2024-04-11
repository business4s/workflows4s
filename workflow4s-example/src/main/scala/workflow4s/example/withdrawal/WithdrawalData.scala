package workflow4s.example.withdrawal

import workflow4s.example.withdrawal.WithdrawalService.{Fee, Iban}
import workflow4s.example.withdrawal.checks.ChecksState

sealed trait WithdrawalData {
  def txId: String
}

object WithdrawalData {
  case class Empty(txId: String)                                                                                         extends WithdrawalData {
    def initiated(amount: BigDecimal, recipient: Iban) = Initiated(txId, amount, recipient)
  }
  case class Initiated(txId: String, amount: BigDecimal, recipient: Iban)                                                extends WithdrawalData {
    def validated(fee: Fee) = Validated(txId, amount, recipient, fee)
  }
  case class Validated(txId: String, amount: BigDecimal, recipient: Iban, fee: Fee)                                      extends WithdrawalData {
    def checked(checksState: ChecksState.Decided): Checked = Checked(txId, amount, recipient, fee, checksState)
    def checking(checksState: ChecksState.InProgress): Checking = Checking(txId, amount, recipient, fee, checksState)
  }
  case class Checking(txId: String, amount: BigDecimal, recipient: Iban, fee: Fee, checkResults: ChecksState.InProgress) extends WithdrawalData
  case class Checked(txId: String, amount: BigDecimal, recipient: Iban, fee: Fee, checkResults: ChecksState.Decided)     extends WithdrawalData {
    def netAmount                      = amount - fee.value
    def executed(externalTxId: String) = Executed(txId, amount, recipient, fee, checkResults, externalTxId)
  }

  case class Executed(txId: String, amount: BigDecimal, recipient: Iban, fee: Fee, checkResults: ChecksState, externalTransactionId: String)
      extends WithdrawalData {

    def completed(): Completed = Completed.Succesfully()

  }

  sealed trait Completed extends WithdrawalData
  object Completed {
    case class Succesfully()         extends Completed {
      override def txId: String = ???
    }
    case class Failed(error: String) extends Completed {
      override def txId: String = ???
    }
  }
}
