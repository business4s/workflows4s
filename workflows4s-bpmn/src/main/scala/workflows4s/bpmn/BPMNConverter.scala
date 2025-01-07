package workflows4s.bpmn

import org.camunda.bpm.model.bpmn.builder.{AbstractActivityBuilder, AbstractFlowNodeBuilder}
import org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnDiagram
import org.camunda.bpm.model.bpmn.instance.di.DiagramElement
import org.camunda.bpm.model.bpmn.instance.{Activity, BaseElement, Definitions, FlowNode}
import org.camunda.bpm.model.bpmn.{Bpmn, BpmnModelInstance}
import workflows4s.wio.model.WIOModel

import java.time.Duration
import scala.jdk.CollectionConverters.CollectionHasAsScala
import scala.util.Random
import scala.util.chaining.scalaUtilChainingOps

object BPMNConverter {

  def convert(model: WIOModel, name: String): BpmnModelInstance = {
    val base = {
      Bpmn
        .createExecutableProcess(name)
        .startEvent()
    }

    handle(model, base)
      .endEvent()
      .done()
      .pipe(assignStableIds)
  }

  private type Builder = AbstractFlowNodeBuilder[? <: AbstractFlowNodeBuilder[?, ? <: FlowNode], ? <: FlowNode]

  private def handle(
      model: WIOModel,
      builder: Builder,
  ): Builder = {
    model match {
      case WIOModel.Sequence(steps)                                   =>
        steps.foldLeft[AbstractFlowNodeBuilder[?, ?]](builder)((builder, step) => handle(step, builder))
      case WIOModel.Dynamic(name, error)                              =>
        val taskName = name.map(_ + " (Dynamic)").getOrElse("<Dynamic>")
        builder
          .serviceTask()
          .name(taskName)
          .pipe(renderError(error))
      case WIOModel.RunIO(error, name)                                =>
        builder
          .serviceTask()
          .name(name.getOrElse("Task"))
          .pipe(renderError(error))
      case WIOModel.HandleSignal(signalName, error, operationName)    =>
        builder
          .intermediateCatchEvent()
          .signal(signalName)
          .name(signalName)
          .serviceTask()
          .name(operationName.getOrElse(s"""Handle "${signalName}""""))
          .pipe(renderError(error))
      case WIOModel.HandleError(base, handler, error)                 =>
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
          .name(error.map(_.name).getOrElse(""))
        handle(handler, errPath)
          .endEvent()
          .moveToNode(subProcessStartEventId)
          .subProcessDone()
      case WIOModel.End                                               => builder
      case WIOModel.Pure(name, errorOpt)                              =>
        if (errorOpt.isDefined || name.isDefined) {
          builder
            .serviceTask()
            .name(name.orNull)
            .pipe(renderError(errorOpt))
        } else builder
      case loop: WIOModel.Loop                                        =>
        val loopStartGwId      = Random.alphanumeric.filter(_.isLetter).take(10).mkString
        val loopEndGwId        = Random.alphanumeric.filter(_.isLetter).take(10).mkString
        val nextTaskTempNodeId = Random.alphanumeric.filter(_.isLetter).take(10).mkString
        val newBuilder         = builder.exclusiveGateway(loopStartGwId).name("")
        handle(loop.base, newBuilder)
          .exclusiveGateway(loopEndGwId)
          .name(loop.conditionName.getOrElse(""))
          .condition(loop.exitBranchName.orNull, "")
          .serviceTask(nextTaskTempNodeId)
          .name("")
          .moveToLastGateway()
          .condition(loop.restartBranchName.orNull, "")
          .pipe(builder =>
            loop.onRestart match {
              case Some(value) => handle(value, builder)
              case None        => builder
            },
          )
          .connectTo(loopStartGwId)
          .moveToNode(nextTaskTempNodeId)
      case WIOModel.Fork(branches, name)                              =>
        val base                           = builder.exclusiveGateway().name(name.orNull)
        val gwId                           = base.getElement.getId
        val (resultBuilder, Some(endGwId)) = {
          branches.zipWithIndex.foldLeft[(Builder, Option[String])](base -> None)({ case ((builder1, endGw), (branch, idx)) =>
            val b2              = builder1.moveToNode(gwId).condition(branch.label.getOrElse(s"Branch ${idx}"), "")
            val result: Builder = handle(branch.logic, b2)
            endGw match {
              case Some(value) =>
                (result.connectTo(value), endGw)
              case None        =>
                val gwId = result.exclusiveGateway().getElement.getId

                (result, Some(gwId))
            }
          })
        }: @unchecked
        resultBuilder.moveToNode(endGwId)
      case WIOModel.Interruptible(base, trigger, interruptionFlowOpt) =>
        val subProcessStartEventId = Random.alphanumeric.filter(_.isLetter).take(10).mkString
        val subBuilder             = builder
          .subProcess()
          .embeddedSubProcess()
          .startEvent(subProcessStartEventId)
          .name("")
        val interruptionPath       = handle(base, subBuilder)
          .endEvent()
          .subProcessDone()
          .boundaryEvent()
          .pipe(builder =>
            trigger match {
              case x: WIOModel.HandleSignal =>
                builder
                  .signal(x.signalName)
                  .name(x.signalName)
                  .serviceTask()
                  .name(s"Handle ${x.signalName}")
                  .pipe(renderError(x.error))
              case x: WIOModel.Timer        =>
                builder
                  .timerWithDuration("")
                  .name(x.name.orNull)
            },
          )
        interruptionFlowOpt
          .map(handle(_, interruptionPath))
          .getOrElse(interruptionPath)
          .endEvent()
          .moveToNode(subProcessStartEventId)
          .subProcessDone()
      case WIOModel.Timer(duration, name)                             =>
        val label = (duration, name) match {
          case (Some(duration), Some(name)) => s"$name (${humanReadableDuration(duration)})"
          case (None, Some(name))           => name
          case (Some(duration), None)       => humanReadableDuration(duration)
          case (None, None)                 => ""
        }
        builder
          .intermediateCatchEvent()
          .timerWithDuration(label)
          .name(label)
    }
  }

  def renderError[B <: AbstractActivityBuilder[B, E], E <: Activity](
      error: Option[WIOModel.Error],
  ): AbstractActivityBuilder[B, E] => Builder = { (x: AbstractActivityBuilder[B, E]) =>
    {
      error match {
        case Some(value) =>
          x.boundaryEvent().error().name(value.name).moveToNode(x.getElement.getId)
        case None        => x
      }
    }
  }

  def humanReadableDuration(duration: Duration): String = duration.toString.substring(2).replaceAll("(\\d[HMS])(?!$)", "$1 ").toLowerCase

  private def assignStableIds(model: BpmnModelInstance): BpmnModelInstance = {
    val allElements = model.getModelElementsByType(classOf[BaseElement]).asScala
    allElements.zipWithIndex.foreach({ case (el, idx) => el.setId(s"${el.getElementType.getTypeName}_${idx}") })
    model
      .getModelElementsByType(classOf[Definitions])
      .asScala
      .zipWithIndex
      .foreach({ case (el, idx) => el.setId(s"${el.getElementType.getTypeName}_${idx}") })
    model
      .getModelElementsByType(classOf[DiagramElement])
      .asScala
      .zipWithIndex
      .foreach({ case (el, idx) => el.setId(s"${el.getClass.getSimpleName}_${idx}") })
    model
      .getModelElementsByType(classOf[BpmnDiagram])
      .asScala
      .zipWithIndex
      .foreach({ case (el, idx) => el.setId(s"BpmnDiagram_${idx}") })
    model
  }

  case class Path(raw: Vector[Int]) {
    def append(id: Int)      = Path(raw.appended(id))
    def asId(prefix: String) = s"${prefix}_${raw.mkString("_")}"
  }
}
