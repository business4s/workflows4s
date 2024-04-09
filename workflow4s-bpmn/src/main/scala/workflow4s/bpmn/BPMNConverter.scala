package workflow4s.bpmn

import org.camunda.bpm.model.bpmn.builder.{AbstractActivityBuilder, AbstractFlowNodeBuilder, ProcessBuilder}
import org.camunda.bpm.model.bpmn.instance.{Activity, FlowNode}
import org.camunda.bpm.model.bpmn.{Bpmn, BpmnModelInstance}
import workflow4s.wio.ErrorMeta
import workflow4s.wio.model.WIOModel

import java.nio.file.Path
import java.util.UUID
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
  }

  private type Builder = AbstractFlowNodeBuilder[? <: AbstractFlowNodeBuilder[?, ? <: FlowNode], ? <: FlowNode]

  private def handle(
      model: WIOModel,
      builder: Builder,
  ): Builder = {
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
          .name(s"""Handle "${signalName}"""")
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
          .name(errName.nameOpt.get)
        handle(handler, errPath)
          .endEvent()
          .moveToNode(subProcessStartEventId)
          .subProcessDone()
      case WIOModel.Noop                                               => builder
      case WIOModel.Pure(_, _, errMeta)                                =>
        errMeta match {
          case ErrorMeta.NoError()  => builder // dont render anything if no error
          case ErrorMeta.Present(_) =>
            builder
              .serviceTask()
              .pipe(renderError(errMeta))
        }
      case WIOModel.Loop(base, conditionName)                          =>
        val loopStartGwId      = Random.alphanumeric.filter(_.isLetter).take(10).mkString
        val newBuilder         = builder.exclusiveGateway(loopStartGwId)
        val loopEndGwId        = Random.alphanumeric.filter(_.isLetter).take(10).mkString
        val nextTaskTempNodeId = Random.alphanumeric.filter(_.isLetter).take(10).mkString
        handle(base, newBuilder)
          .exclusiveGateway(loopEndGwId)
          .serviceTask(nextTaskTempNodeId)
          .moveToLastGateway()
          .connectTo(loopStartGwId)
          .moveToNode(nextTaskTempNodeId)
      case WIOModel.Fork(branches)                                     =>
        val base                           = builder.exclusiveGateway()
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
        }
        resultBuilder.moveToNode(endGwId)
      case WIOModel.Interruptible(base, trigger, interruptionFlowOpt)  =>
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
            },
          )
        interruptionFlowOpt
          .map(handle(_, interruptionPath))
          .getOrElse(interruptionPath)
          .endEvent()
          .moveToNode(subProcessStartEventId)
          .subProcessDone()
    }
  }

  def renderError[B <: AbstractActivityBuilder[B, E], E <: Activity](
      error: ErrorMeta[_],
  ): AbstractActivityBuilder[B, E] => Builder = { (x: AbstractActivityBuilder[B, E]) =>
    {
      error.nameOpt match {
        case Some(value) =>
          x.boundaryEvent().error().name(value).moveToNode(x.getElement.getId)
        case None        => x
      }
    }
  }

}
