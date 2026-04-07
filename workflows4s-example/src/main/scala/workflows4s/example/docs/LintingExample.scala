package workflows4s.example.docs

import workflows4s.wio.{Linter, LinterIssue, WIO}

object LintingExample {

  // start_doc
  val workflow: WIO[?, ?, ?, ?] = ???
  val issues: List[LinterIssue] = Linter.lint(workflow)
  // Each issue contains: message, ruleId, and path
  // end_doc

}
