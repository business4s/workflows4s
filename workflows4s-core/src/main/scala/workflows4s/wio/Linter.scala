package workflows4s.wio

import cats.effect.IO
import workflows4s.wio.linter.{BusyLoopRule, ClashingEventsRule, ClashingSignalsRule, UnnecessaryErrorHandlerRule}

case class LinterIssue(message: String, ruleId: String, path: List[String]) {
  override def toString: String = s"[${ruleId}] ${path.mkString(" > ")}: ${message}"
}

object Linter {

  def lint(wio: WIO[IO, ?, ?, ?, ?]): List[LinterIssue] = {
    val rules = List(ClashingSignalsRule, BusyLoopRule, UnnecessaryErrorHandlerRule, ClashingEventsRule)
    rules.flatMap(_.check(wio))
  }

  trait Rule {
    def id: String
    def check(wio: WIO[IO, ?, ?, ?, ?]): List[LinterIssue]
  }

}
