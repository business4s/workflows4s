package workflow4s.bpmn

import org.camunda.bpm.model.bpmn.builder.{AbstractActivityBuilder, AbstractFlowNodeBuilder, ProcessBuilder}
import org.camunda.bpm.model.bpmn.instance.{Activity, FlowNode}
import org.camunda.bpm.model.bpmn.{Bpmn, BpmnModelInstance}
import workflow4s.wio.model.WIOModel

import java.nio.file.Path
import java.util.UUID
import scala.util.Random
import scala.util.chaining.scalaUtilChainingOps

object BPMNConverter {

  def convert(model: WIOModel, name: String): BpmnModelInstance = {
    val base =
      Bpmn
        .createExecutableProcess(name)
        .startEvent()

    handle(model, base)
      .endEvent()
      .done()
  }

  private def handle[B <: AbstractFlowNodeBuilder[B, E], E <: FlowNode](
      model: WIOModel,
      builder: AbstractFlowNodeBuilder[B, E],
  ): AbstractFlowNodeBuilder[_, _] =
    model match {
      case WIOModel.Sequence(steps)                                    =>
        steps.foldLeft[AbstractFlowNodeBuilder[_, _]](builder)((builder, step) => handle(step, builder))
      case WIOModel.Dynamic(name, error)                               =>
        val taskName = name.map(_ + " (Dynamic)").getOrElse("<Dynamic>")
        builder
          .serviceTask()
          .name(taskName)
          .pipe(renderError(error))
      case WIOModel.RunIO(error, name, description)                    =>
        builder
          .serviceTask()
          .name(name.getOrElse("Task"))
          .documentation(description.orNull)
          .pipe(renderError(error))
      case WIOModel.HandleSignal(signalName, error, name, description) =>
        builder
          .intermediateCatchEvent()
          .signal(signalName)
          .name(signalName)
          .serviceTask()
          .name(s"Handle ${signalName}")
          .documentation(description.orNull)
          .pipe(renderError(error))
      case WIOModel.HandleError(base, handler, errName)                =>
        val subProcessStartEventId = Random.alphanumeric.filter(_.isLetter).take(10).mkString
        val subBuilder             = builder
          .subProcess()
          .embeddedSubProcess()
          .startEvent(subProcessStartEventId)
          .name("")
        val errPath                = handle(base, subBuilder)
          .endEvent()
          .subProcessDone()
          .boundaryEvent()
          .error()
          .name(errName.getOrElse(""))
        handle(handler, errPath)
          .endEvent()
          .moveToNode(subProcessStartEventId)
          .subProcessDone()
      case WIOModel.Noop                                               => builder
      case WIOModel.Pure(_, _)                                         => builder // TODO remove name if we dont render
    }

  def renderError[B <: AbstractActivityBuilder[B, E], E <: Activity](
      error: Option[WIOModel.Error],
  ): AbstractActivityBuilder[B, E] => (AbstractFlowNodeBuilder[B, E]) forSome { type B <: AbstractFlowNodeBuilder[B, E]; type E <: FlowNode } =
    (x: AbstractActivityBuilder[B, E]) =>
      error match {
        case Some(value) =>
          x.boundaryEvent().error().name(value.name).moveToNode(x.getElement.getId)
        case None        => x
      }

}
