package workflows4s.wio

import workflows4s.wio.linter.{BusyLoopRule, ClashingEventsRule, ClashingSignalsRule, UnnecessaryErrorHandlerRule}

case class LinterIssue(message: String, ruleId: String, path: List[String]) {
  override def toString: String = s"[${ruleId}] ${path.mkString(" > ")}: ${message}"
}

/** Static analysis of workflow definitions. Detects issues like clashing signal/event handlers,
  * busy loops, and unreachable error handlers without executing the workflow.
  */
object Linter {

  def lint(wio: WIO[?, ?, ?, ?]): List[LinterIssue] = {
    val rules = List(ClashingSignalsRule, BusyLoopRule, UnnecessaryErrorHandlerRule, ClashingEventsRule)
    rules.flatMap(_.check(wio))
  }

  trait Rule {
    def id: String
    def check(wio: WIO[?, ?, ?, ?]): List[LinterIssue]
  }

}
