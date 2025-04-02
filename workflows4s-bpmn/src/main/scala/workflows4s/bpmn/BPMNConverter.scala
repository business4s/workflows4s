package workflows4s.bpmn

import org.camunda.bpm.model.bpmn.BpmnModelInstance
import workflows4s.wio.model.WIOModel

object BPMNConverter {

  /**
   * @deprecated Use BpmnRenderer.renderWorkflow instead
   */
  @deprecated("Use BpmnRenderer.renderWorkflow instead", "")
  def convert(model: WIOModel, name: String): BpmnModelInstance = {
    BpmnRenderer.renderWorkflow(model, name)
  }



}
