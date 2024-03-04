package workflow4s.bpmn

import org.camunda.bpm.model.bpmn.builder.{AbstractFlowNodeBuilder, ProcessBuilder}
import org.camunda.bpm.model.bpmn.instance.FlowNode
import org.camunda.bpm.model.bpmn.{Bpmn, BpmnModelInstance}
import workflow4s.wio.model.WIOModel

import java.nio.file.Path

object BPMNConverter {

  def convert(model: WIOModel, name: String): BpmnModelInstance = {
    val base =
      Bpmn
        .createExecutableProcess(name)
        .startEvent()

    handle(model, base)
      .done()
  }

  private def handle[B <: AbstractFlowNodeBuilder[B, E], E <: FlowNode](
      model: WIOModel,
      builder: AbstractFlowNodeBuilder[B, E],
  ): AbstractFlowNodeBuilder[_, _] =
    model match {
      case WIOModel.Sequence(steps)                                    =>
        steps.foldLeft[AbstractFlowNodeBuilder[_, _]](builder)((builder, step) => handle(step, builder))
      case WIOModel.Dynamic()                                          => builder.serviceTask().name("Dynamic")
      case WIOModel.RunIO(error, name, description)                    => builder.serviceTask().name(name.getOrElse("Task")).documentation(description.orNull)
      case WIOModel.HandleSignal(signalName, error, name, description) =>
        builder
          .intermediateCatchEvent()
          .signal(signalName)
          .name(signalName)
          .serviceTask()
          .name(name.getOrElse("SignalHandler"))
          .documentation(description.orNull)
      case WIOModel.HandleError(base, handler)                         => handle(base, builder)
      case WIOModel.Noop                                               => builder
    }

}
