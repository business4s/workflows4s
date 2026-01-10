package workflows4s.example.pekko

import workflows4s.example.withdrawal.WithdrawalEvent
import workflows4s.example.withdrawal.checks.ChecksEvent

class WithdrawalEventPekkoSerializer extends PekkoCirceSerializer[WithdrawalEvent] {
  override def identifier = 12345678
}

class ChecksEventPekkoSerializer extends PekkoCirceSerializer[ChecksEvent] {
  override def identifier = 12345677
}
