package workflows4s.bpmn

import org.camunda.bpm.model.bpmn.builder.{AbstractActivityBuilder, AbstractFlowNodeBuilder}
import org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnDiagram
import org.camunda.bpm.model.bpmn.instance.di.DiagramElement
import org.camunda.bpm.model.bpmn.instance.{Activity, BaseElement, Definitions, FlowNode}
import org.camunda.bpm.model.bpmn.{Bpmn, BpmnModelInstance}
import workflows4s.RenderUtils
import workflows4s.wio.model.{WIOMeta, WIOModel}

import java.time.Duration
import scala.jdk.CollectionConverters.CollectionHasAsScala
import scala.util.Random
import scala.util.chaining.scalaUtilChainingOps

object BpmnRenderer {

  def renderWorkflow(model: WIOModel, name: String): BpmnModelInstance = {
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
      case WIOModel.Dynamic(meta)                                     =>
        builder
          .serviceTask()
          .name("<Dynamic>")
          .pipe(renderError(meta.error))
      case WIOModel.RunIO(meta)                                       =>
        builder
          .serviceTask()
          .name(meta.name.getOrElse("Task"))
          .pipe(renderError(meta.error))
      case WIOModel.HandleSignal(meta)                                =>
        builder
          .intermediateCatchEvent()
          .signal(meta.signalName)
          .name(meta.signalName)
          .serviceTask()
          .name(meta.operationName.getOrElse(s"""Handle "${meta.signalName}""""))
          .pipe(renderError(meta.error))
      case WIOModel.HandleError(base, handler, meta)                  =>
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
          .name(meta.handledErrorMeta.map(_.name).getOrElse(""))
        handle(handler, errPath)
          .endEvent()
          .moveToNode(subProcessStartEventId)
          .subProcessDone()
      case WIOModel.End                                               => builder
      case WIOModel.Pure(meta)                                        =>
        if meta.error.isDefined || meta.name.isDefined then {
          builder
            .serviceTask()
            .name(meta.name.orNull)
            .pipe(renderError(meta.error))
        } else builder
      case loop: WIOModel.Loop                                        =>
        val loopStartGwId      = Random.alphanumeric.filter(_.isLetter).take(10).mkString
        val loopEndGwId        = Random.alphanumeric.filter(_.isLetter).take(10).mkString
        val nextTaskTempNodeId = Random.alphanumeric.filter(_.isLetter).take(10).mkString
        val newBuilder         = builder.exclusiveGateway(loopStartGwId).name("")
        handle(loop.base, newBuilder)
          .exclusiveGateway(loopEndGwId)
          .name(loop.meta.conditionName.getOrElse(""))
          .condition(loop.meta.exitBranchName.orNull, "")
          .serviceTask(nextTaskTempNodeId)
          .name("")
          .moveToNode(loopEndGwId)
          .condition(loop.meta.restartBranchName.orNull, "")
          .pipe(b => loop.onRestart.map(handle(_, b)).getOrElse(b))
          .connectTo(loopStartGwId)
          .moveToNode(nextTaskTempNodeId)
      case WIOModel.Fork(branches, meta)                              =>
        val base                           = builder.exclusiveGateway().name(meta.name.orNull)
        val gwId                           = base.getElement.getId
        val (resultBuilder, Some(endGwId)) = {
          branches.zipWithIndex.foldLeft[(Builder, Option[String])](base -> None)({ case ((builder1, endGw), (branch, idx)) =>
            val b2              = builder1.moveToNode(gwId).condition(meta.branches.lift(idx).flatMap(_.name).getOrElse(s"Branch ${idx}"), "")
            val result: Builder = handle(branch, b2)
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
                  .signal(x.meta.signalName)
                  .name(x.meta.signalName)
                  .serviceTask()
                  .name(s"Handle ${x.meta.signalName}")
                  .pipe(renderError(x.meta.error))
              case x: WIOModel.Timer        =>
                builder
                  .timerWithDuration("")
                  .name(x.meta.name.orNull)
            },
          )
        interruptionFlowOpt.map(handle(_, interruptionPath)).getOrElse(interruptionPath)
      case WIOModel.Timer(meta)                                       =>
        val durationStr = meta.duration.map(RenderUtils.humanReadableDuration).getOrElse("dynamic")
        builder
          .intermediateCatchEvent()
          .timerWithDuration(durationStr)
          .name(meta.name.orNull)
      case WIOModel.Parallel(elems)                                   =>
        val base                           = builder.parallelGateway()
        val gwId                           = base.getElement.getId
        val (resultBuilder, Some(endGwId)) = {
          elems.foldLeft[(Builder, Option[String])](base -> None)({ case ((builder1, endGw), branch) =>
            val b2              = builder1.moveToNode(gwId)
            val result: Builder = handle(branch, b2)
            endGw match {
              case Some(value) =>
                (result.connectTo(value), endGw)
              case None        =>
                val gwId = result.parallelGateway().getElement.getId
                (result, Some(gwId))
            }
          })
        }: @unchecked
        resultBuilder.moveToNode(endGwId)
      case WIOModel.Checkpoint(base)                                  => handle(base, builder)
      case WIOModel.Recovery()                                        => builder
      case WIOModel.Retried(_)                                        => builder
      case WIOModel.ForEach(_, _)                                     => ???
    }
  }

  def renderError[B <: AbstractActivityBuilder[B, E], E <: Activity](
      error: Option[WIOMeta.Error],
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
}
