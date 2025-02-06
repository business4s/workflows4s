package workflows4s.wio.internal

import cats.implicits.catsSyntaxOptionId
import workflows4s.wio
import workflows4s.wio.*
import workflows4s.wio.model.WIOExecutionProgress

/** Renders WIO as a debugging string, that contains information about executed steps and minimal information about future ones
  */
object DebugEvaluator {
  def getCurrentStateDescription(
      wio: WIO[?, ?, ?, ?],
  ): String = {
    renderModel(wio.toProgress).render
  }

  def renderModel(model: WIOExecutionProgress[?]): Description = {
    val tpe                  = model match {
      case _: WIOExecutionProgress.Sequence[?]      => "Sequence"
      case _: WIOExecutionProgress.Dynamic          => "Dynamic"
      case _: WIOExecutionProgress.RunIO[?]         => "RunIO"
      case _: WIOExecutionProgress.HandleSignal[?]  => "HandleSignal"
      case _: WIOExecutionProgress.HandleError[?]   => "HandleError"
      case _: WIOExecutionProgress.End[?]           => "End"
      case _: WIOExecutionProgress.Pure[?]          => "Pure"
      case _: WIOExecutionProgress.Loop[?]          => "Loop"
      case _: WIOExecutionProgress.Fork[?]          => "Fork"
      case _: WIOExecutionProgress.Interruptible[?] => "Interruptible"
      case _: WIOExecutionProgress.Timer[?]         => "Timer"
    }
    val name                 = model match {
      case x: WIOExecutionProgress.Sequence[?]      => None
      case x: WIOExecutionProgress.Dynamic          => None
      case x: WIOExecutionProgress.RunIO[?]         => x.meta.name
      case x: WIOExecutionProgress.HandleError[?]   => None
      case x: WIOExecutionProgress.HandleSignal[?]  => x.meta.operationName
      case x: WIOExecutionProgress.End[?]           => None
      case x: WIOExecutionProgress.Pure[?]          => x.meta.name
      case x: WIOExecutionProgress.Loop[?]          => None
      case x: WIOExecutionProgress.Fork[?]          => x.meta.name
      case x: WIOExecutionProgress.Interruptible[?] => None
      case x: WIOExecutionProgress.Timer[?]         => x.meta.name
    }
    val description          = model match {
      case x: WIOExecutionProgress.Sequence[?]      => None
      case x: WIOExecutionProgress.Dynamic          => None
      case x: WIOExecutionProgress.RunIO[?]         => None
      case x: WIOExecutionProgress.HandleError[?]   => None
      case x: WIOExecutionProgress.HandleSignal[?]  => s"Signal: ${x.meta.signalName}".some
      case x: WIOExecutionProgress.End[?]           => None
      case x: WIOExecutionProgress.Pure[?]          => None
      case x: WIOExecutionProgress.Loop[?]          => None
      case x: WIOExecutionProgress.Fork[?]          => None
      case x: WIOExecutionProgress.Interruptible[?] => None
      case x: WIOExecutionProgress.Timer[?]         => x.meta.duration.map(_.toString)
    }
    val children             = model match {
      case x: WIOExecutionProgress.Sequence[?]      =>
        val (executed, nonExecuted) = x.steps.partition(_.isExecuted)
        val executedDesc            = executed.zipWithIndex.map((step, idx) => renderChild(s"step $idx", step))
        val firstNonExecuted        = nonExecuted.headOption.map(step => renderChild(s"step ${executed.size}", step))
        val remainingDesc           = Option.when(nonExecuted.size > 1)(Description(s"${nonExecuted.size - 1} more steps", Seq()))
        executedDesc ++ firstNonExecuted ++ remainingDesc
      case x: WIOExecutionProgress.Dynamic          => Seq()
      case x: WIOExecutionProgress.RunIO[?]         => Seq()
      case x: WIOExecutionProgress.HandleError[?]   =>
        // TODO should render handler without its children unless handler was entered.
        renderChildren("base" -> x.base, "handler" -> x.handler)
      case x: WIOExecutionProgress.HandleSignal[?]  => Seq()
      case x: WIOExecutionProgress.End[?]           => Seq()
      case x: WIOExecutionProgress.Pure[?]          => Seq()
      case x: WIOExecutionProgress.Loop[?]          =>
        // TODO label should change between "going forward" and "going backward"
        x.history.zipWithIndex.map((step, idx) => renderChild(s"loop $idx", step))
      case x: WIOExecutionProgress.Fork[?]          =>
        x.selected match {
          case Some(selectedIdx) =>
            Seq(
              renderChild(s"branch ${selectedIdx}", x.branches(selectedIdx)),
              Description(s"${x.branches.size - 1} more branches", Seq()),
            )
          case None              => x.branches.zipWithIndex.map((step, idx) => renderChild(s"branch $idx", step))
        }
      case x: WIOExecutionProgress.Interruptible[?] =>
        // TODO discard handler and trigger if base passed
        Seq(renderChild("base", x.base)) ++ Option
          .when(x.base.isExecuted)(Seq(renderChild("trigger", x.trigger)) ++ x.handler.map(x => renderChild("handler", x)))
          .getOrElse(Seq())
      case x: WIOExecutionProgress.Timer[?]         => Seq()
    }
    val effectiveDescription = if (model.isExecuted) s"Executed: ${model.result.get.merge}" else description.getOrElse("")
    val effectiveChildren    = if (model.isExecuted) Seq() else children
    formatNode(s"$tpe", name.getOrElse("no-name"), effectiveDescription, effectiveChildren)
  }

  def renderChild(name: String, elem: WIOExecutionProgress[?]): Description                                                = {
    renderModel(elem).prepend(s"$name: ")
  }
  def renderChildren(children: (String, WIOExecutionProgress[?])*): Seq[Description]                                       = {
    children.map(renderChild.tupled)
  }
  private def formatNode(nodeType: String, name: String, details: String, children: Seq[Description] = Seq()): Description = {
    Description(s"[$nodeType]($name) $details", children)
  }

  case class Description(headline: String, children: Seq[Description]) {

    def prepend(str: String): Description = Description(str + headline, children)

    def render: String = {
      val childrenStr =
        if (children.nonEmpty) "\n" + children.map(x => s"- ${x.render}").mkString("\n").indent(2).stripSuffix("\n")
        else ""
      s"$headline$childrenStr"
    }
  }
}
