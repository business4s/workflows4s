package workflows4s.wio.internal

import cats.implicits.catsSyntaxOptionId
import workflows4s.{RenderUtils, wio}
import workflows4s.wio.*
import workflows4s.wio.model.WIOExecutionProgress

import scala.annotation.nowarn

/** Renders WIO as a debugging string, that contains information about executed steps and minimal information about future ones
  */
@nowarn("msg=unused private member")
object DebugRenderer {

  def getCurrentStateDescription(model: WIOExecutionProgress[?]): String =
    renderModel(model).render

  private def renderModel(model: WIOExecutionProgress[?]): Description = {
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
      case _: WIOExecutionProgress.Parallel[?]      => "Parallel"
      case x: WIOExecutionProgress.Checkpoint[?]    => "Checkpoint"
      case x: WIOExecutionProgress.Recovery[?]      => "Recovery"
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
      case _: WIOExecutionProgress.Parallel[?]      => None
      case _: WIOExecutionProgress.Checkpoint[?]    => None
      case _: WIOExecutionProgress.Recovery[?]      => None
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
      case x: WIOExecutionProgress.Timer[?]         => x.meta.duration.map(RenderUtils.humanReadableDuration).orElse(x.meta.releaseAt.map(_.toString))
      case _: WIOExecutionProgress.Parallel[?]      => None
      case _: WIOExecutionProgress.Checkpoint[?]    => None
      case _: WIOExecutionProgress.Recovery[?]      => None
    }
    val children             = model match {
      case x: WIOExecutionProgress.Sequence[?]      =>
        val (executed, nonExecuted) = x.steps.partition(_.isExecuted)
        val executedDesc            = executed.zipWithIndex.map((step, idx) => renderChild(s"step $idx", step))
        val firstNonExecuted        = nonExecuted.headOption.map(step => renderChild(s"step ${executed.size}", step))
        val remainingDesc           = Option.when(nonExecuted.size > 1)(Description(s"${nonExecuted.size - 1} more steps", Seq()))
        executedDesc ++ firstNonExecuted ++ remainingDesc
      case _: WIOExecutionProgress.Dynamic          => Seq()
      case _: WIOExecutionProgress.RunIO[?]         => Seq()
      case x: WIOExecutionProgress.HandleError[?]   =>
        Seq(renderChild("base", x.base)) ++
          Option.when(RenderUtils.hasStarted(x.handler))(renderChild("handler", x.handler))
      case _: WIOExecutionProgress.HandleSignal[?]  => Seq()
      case _: WIOExecutionProgress.End[?]           => Seq()
      case _: WIOExecutionProgress.Pure[?]          => Seq()
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
        Seq(renderChild("base", x.base)) ++ Option
          .when(!x.base.isExecuted)(Seq(renderChild("trigger", x.trigger)) ++ x.handler.map(x => renderChild("handler", x)))
          .getOrElse(Seq())
      case _: WIOExecutionProgress.Timer[?]         => Seq()
      case x: WIOExecutionProgress.Parallel[?]      => x.elements.zipWithIndex.map((elem, idx) => renderChild(s"branch ${idx}", elem))
      case x: WIOExecutionProgress.Checkpoint[?]    => renderChildren("base" -> x.base)
      case x: WIOExecutionProgress.Recovery[?]      => Seq()
    }
    val effectiveDescription = if model.isExecuted then s"Executed: ${model.result.get.merge}" else description.getOrElse("")
    val effectiveChildren    = if model.isExecuted then Seq() else children
    formatNode(s"$tpe", name.getOrElse("no-name"), effectiveDescription, effectiveChildren)
  }

  private def renderChild(name: String, elem: WIOExecutionProgress[?]): Description                                        = {
    renderModel(elem).prepend(s"$name: ")
  }
  private def renderChildren(children: (String, WIOExecutionProgress[?])*): Seq[Description]                               = {
    children.map(renderChild.tupled)
  }
  private def formatNode(nodeType: String, name: String, details: String, children: Seq[Description] = Seq()): Description = {
    Description(s"[$nodeType]($name) $details", children)
  }

  private case class Description(headline: String, children: Seq[Description]) {

    def prepend(str: String): Description = Description(str + headline, children)

    def render: String = {
      val childrenStr =
        if children.nonEmpty then "\n" + children.map(x => s"- ${x.render}").mkString("\n").indent(2).stripSuffix("\n")
        else ""
      s"$headline$childrenStr"
    }
  }
}
