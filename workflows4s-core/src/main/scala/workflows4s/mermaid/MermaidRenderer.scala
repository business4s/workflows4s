package workflows4s.mermaid

import cats.data.State
import cats.syntax.all.*
import workflows4s.RenderUtils
import workflows4s.wio.model.{WIOExecutionProgress, WIOMeta}

import java.time.Duration
import scala.annotation.nowarn
import scala.util.chaining.scalaUtilChainingOps

@nowarn("msg=unused private member")
object MermaidRenderer {

  def renderWorkflow(model: WIOExecutionProgress[?], showTechnical: Boolean = false): MermaidFlowchart = {
    (addNode(
      id => Node(id, "Start", shape = "circle".some, clazz = if RenderUtils.hasStarted(model) then executedClass.some else None),
      active = true,
    ) *>
      render(model, showTechnical))
      .run(RenderState.initial(0))
      .value
      ._1
      .chart
      .addElement(styles)
      .addElement(checkpointStyles)
      .addElement(checkpointExecutedStyles)
  }

  private val executedClass            = "executed"
  private val checkpointClass          = "checkpoint"
  private val checkpointExecutedClass  = "checkpoint-executed"
  private val styles                   = ClassDef(executedClass, "fill:#0e0")
  private val checkpointStyles         = ClassDef(checkpointClass, "fill:transparent,stroke-dasharray:5 5,stroke:black")
  private val checkpointExecutedStyles = ClassDef(checkpointExecutedClass, "fill:transparent,stroke-dasharray:5 5,stroke:#0e0")

  // returns id of the first element rendered
  private def render(model: WIOExecutionProgress[?], showTechnical: Boolean = false): State[RenderState, Option[NodeId]] = {
    def go(model: WIOExecutionProgress[?]): State[RenderState, Option[NodeId]] = {
      def addStep(label: String, shape: Option[String] = None): State[RenderState, NodeId] = {
        for {
          isRetried     <- areRetriesEnabled()
          effectiveLabel = if isRetried then s"fa:fa-redo $label" else label
          result        <- addStepGeneral(id => Node(id, effectiveLabel, shape, clazz = if model.isExecuted then executedClass.some else None))
        } yield result

      }
      model match {
        case WIOExecutionProgress.Sequence(steps)                             =>
          for {
            subSteps <- steps.traverse(s => go(s))
          } yield subSteps.flatten.headOption
        case WIOExecutionProgress.Dynamic(meta)                               =>
          for {
            stepId <- addStep("@dynamic")
            _      <- meta.error.traverse(addPendingError(stepId, _))
          } yield stepId.some
        case WIOExecutionProgress.RunIO(meta, _)                              =>
          for {
            stepId <- addStep(meta.name.getOrElse("@computation"))
            _      <- meta.description.traverse(addNote)
            _      <- meta.error.traverse(addPendingError(stepId, _))
          } yield stepId.some
        case WIOExecutionProgress.HandleSignal(meta, _)                       =>
          for {
            signalId <- addStepGeneral(id =>
                          Node(
                            id,
                            s"fa:fa-envelope ${meta.signalName}",
                            shape = eventShape.some,
                            clazz = if model.isExecuted then executedClass.some else None,
                          ),
                        )
            stepId   <- addStep(meta.operationName.getOrElse(s"Handle ${meta.signalName}"))
            _        <- meta.error.traverse(addPendingError(stepId, _))
          } yield signalId.some
        case WIOExecutionProgress.HandleError(base, handler, error, _)        =>
          // generalized error label is unused as we connect specific errors directly to the handler
          for {
            baseStart       <- go(base)
            baseEnds        <- cleanActiveNodes
            errors          <- cleanPendingErrors
            handlerStartOpt <- go(handler)
            handlerStart     = handlerStartOpt.getOrElse(
                                 throw new Exception(s"""Rendering error handler didn't produce a node. This is unexpected, please report as a bug.
                                                    |Handler: ${handler}""".stripMargin),
                               )
            _               <- handleErrors(errors, handlerStart)
            _               <- State.modify[RenderState] { s => s.copy(activeNodes = baseEnds ++ s.activeNodes) }
          } yield baseStart
        case WIOExecutionProgress.End(_)                                      =>
          addStep("End", shape = "circle".some).map(_.some)
        case WIOExecutionProgress.Pure(meta, _)                               =>
          if meta.name.isDefined || meta.error.isDefined then {
            for {
              stepId <- addStep(meta.name.getOrElse("@computation"))
              _      <- meta.error.traverse(addPendingError(stepId, _))
            } yield stepId.some
          } else State.pure(None)
        case WIOExecutionProgress.Loop(base, onRestart, meta, history)        =>
          // history contains `current` and this is prefilled on creation,
          // hence empty nodes have history of 1 with not started node
          if history.size > 1 || history.lastOption.exists(RenderUtils.hasStarted) then {
            // TODO we could render conditions as well
            for {
              subSteps <- history.traverse(h => go(h))
            } yield subSteps.flatten.headOption
          } else {
            for {
              baseStart     <- go(base.toEmptyProgress)
              conditionNode <- addStep(meta.conditionName.getOrElse(" "), shape = conditionShape.some)
              _             <- setNextLinkLabel(meta.restartBranchName)
              _             <- onRestart.flatTraverse(x => go(x.toEmptyProgress))
              ends          <- cleanActiveNodes
              _             <- addLinks(ends, baseStart.get)
              _             <- setActiveNodes(Seq(conditionNode -> meta.exitBranchName))
            } yield baseStart
          }
        case WIOExecutionProgress.Fork(branches, meta, _)                     => {
          def renderBranch(
              branch: WIOExecutionProgress[?],
              meta: WIOMeta.Branch,
              conditionNode: NodeId,
          ): State[RenderState, Vector[(NodeId, NextLinkLabel)]] = {
            for {
              _           <- cleanActiveNodes
              branchStart <- go(branch)
              _           <- branchStart.traverse(addLink(conditionNode, _, label = meta.name))
              branchEnds  <- cleanActiveNodes
            } yield branchEnds.toVector
          }

          for {
            conditionNode <- addStep(meta.name.getOrElse(" "), shape = conditionShape.some)
            ends          <- branches.zipWithIndex.flatTraverse((b, idx) => renderBranch(b, meta.branches(idx), conditionNode))
            _             <- setActiveNodes(ends)
          } yield conditionNode.some
        }
        case WIOExecutionProgress.Interruptible(base, trigger, handleFlow, _) =>
          for {
            (baseEnds, baseStart, _) <- addSubgraph(go(base))
            _                        <- go(trigger)
            _                        <- handleFlow.traverse(h => go(h))
            _                        <- State.modify[RenderState](s => s.copy(activeNodes = s.activeNodes ++ baseEnds))
          } yield baseStart
        case WIOExecutionProgress.Timer(meta, _)                              =>
          val durationStr = meta.duration.map(RenderUtils.humanReadableDuration).getOrElse("dynamic")
          val label       = s"${meta.name.getOrElse("")} ($durationStr)"
          for {
            stepId <-
              addStepGeneral(id =>
                Node(id, s"fa:fa-clock ${label}", shape = eventShape.some, clazz = if model.isExecuted then executedClass.some else None),
              )
          } yield stepId.some
        case WIOExecutionProgress.Parallel(elems, _)                          =>
          for {
            forkId <-
              addStepGeneral(id => Node(id, "", shape = forkShape.some, clazz = if RenderUtils.hasStarted(model) then executedClass.some else None))
            ends   <- elems.toList.flatTraverse(element =>
                        for {
                          _    <- setActiveNodes(Seq((forkId, None)))
                          _    <- go(element)
                          ends <- cleanActiveNodes
                        } yield ends.toList,
                      )
            _      <- setActiveNodes(ends)
            endId  <- addStepGeneral(id => Node(id, "", shape = forkShape.some, clazz = if model.isExecuted then executedClass.some else None))
          } yield forkId.some

        case WIOExecutionProgress.Checkpoint(base, result) =>
          if showTechnical then {
            val checkpointClz = if model.isExecuted then checkpointExecutedClass else checkpointClass
            for {
              (baseEnds, baseStart, _) <- addSubgraph(go(base), "Checkpoint", Some(checkpointClz))
              _                        <- setActiveNodes(baseEnds)
            } yield baseStart
          } else go(base)

        case WIOExecutionProgress.Recovery(result)           =>
          // This is a recovery-only checkpoint (created with WIO.recover)
          if showTechnical then addStepGeneral(id =>
            Node(id, "fa:fa-wrench State Recovery", shape = "hexagon".some, clazz = if model.isExecuted then executedClass.some else None),
          ).map(_.some)
          else State.pure(None)
        case WIOExecutionProgress.Retried(base)              =>
          enableRetries() *> go(base) <* disableRetries()
        case WIOExecutionProgress.ForEach(_, model, _, meta) =>
          for {
            activeNodes                  <- cleanActiveNodes
            name                          = s"≡ ${meta.name.getOrElse("For Each")}"
            (baseEnds, baseStart, subId) <- addSubgraph(go(model.toEmptyProgress), name, None)
            _                            <- addLinks(activeNodes, subId)
            _                            <- setActiveNodes(baseEnds)
          } yield baseStart
      }
    }

    go(model)
  }

  private val eventShape     = "stadium"
  private val conditionShape = "hex"
  private val forkShape      = "fork"

  private def cleanActiveNodes: State[RenderState, Seq[ActiveNode]]            = State { s => s.copy(activeNodes = Seq()) -> s.activeNodes }
  private def cleanPendingErrors: State[RenderState, Seq[PendingError]]        = State { s => s.copy(pendingErrors = Seq()) -> s.pendingErrors }
  private def setActiveNodes(nodes: Seq[ActiveNode]): State[RenderState, Unit] = State.modify(_.copy(activeNodes = nodes))
  private def setNextLinkLabel(label: NextLinkLabel): State[RenderState, Unit] = State.modify { s =>
    s.copy(activeNodes = s.activeNodes.map(_._1 -> label))
  }
  private def enableRetries(): State[RenderState, Unit]                        = State.modify(_.copy(underRetrying = true))
  private def disableRetries(): State[RenderState, Unit]                       = State.modify(_.copy(underRetrying = false))
  private def areRetriesEnabled(): State[RenderState, Boolean]                 = State.inspect(_.underRetrying)

  private def addNode(nodeF: NodeId => MermaidElement, active: Boolean, label: Option[String] = None): State[RenderState, NodeId] = State { s =>
    val nodeId = s"node${s.idIdx}"
    s.copy(
      chart = s.chart.addElement(nodeF(nodeId)),
      idIdx = s.idIdx + 1,
      activeNodes = if active then s.activeNodes.appended(nodeId -> label) else s.activeNodes,
    ) -> nodeId
  }
  private def addStepGeneral(createElem: NodeId => MermaidElement): State[RenderState, NodeId]                                    = {
    for {
      prev <- cleanActiveNodes
      id   <- addNode(createElem, active = true)
      _    <- addLinks(prev, id)
    } yield id
  }

  private def addNote(text: String): State[RenderState, Unit] = {
    for {
      activeNodes <- cleanActiveNodes
      note        <- addStepGeneral(id => Node(id, text, Some("braces")))
      _           <- addLinks(activeNodes, note, customize = _.copy(midfix = ".", suffix = ""))
      _           <- setActiveNodes(activeNodes)
    } yield ()
  }

  private def addPendingError(from: NodeId, err: WIOMeta.Error): State[RenderState, Unit] =
    State.modify(_.addPendingError(from, err))

  private def handleErrors(errors: Seq[(NodeId, WIOMeta.Error)], to: NodeId): State[RenderState, Unit]                               = State.modify { state =>
    val links = errors.map(pendingErr => Link(pendingErr._1, to, s"fa:fa-bolt ${pendingErr._2.name}".some, midfix = "."))
    state.addElements(links)
  }
  private def addLink(from: NodeId, to: NodeId, label: Option[String] = None): State[RenderState, Unit]                              =
    addLinks(Seq((from, label)), to)
  private def addLinks(from: Seq[(NodeId, NextLinkLabel)], to: NodeId, customize: Link => Link = identity): State[RenderState, Unit] =
    State.modify(_.addElements(from.map(f => Link(f._1, to, f._2).pipe(customize))))

  private def addSubgraph[T](
      subgraph: State[RenderState, T],
      title: String = " ",
      clazz: Option[String] = None,
  ): State[RenderState, (Seq[ActiveNode], T, NodeId)] = State { state =>
    val id                  = s"subgraph${state.idIdx}"
    val (subState, subProc) = subgraph.run(RenderState.initial(state.idIdx + 1).copy(activeNodes = state.activeNodes)).value
    (
      state.copy(
        chart = state.chart.addElement(Subgraph(id, title, subState.chart.elements, clazz)),
        idIdx = state.idIdx + subState.idIdx + 1,
        activeNodes = Seq((id, None)),
        pendingErrors = state.pendingErrors ++ subState.pendingErrors,
      ),
      (subState.activeNodes, subProc, id),
    )
  }

  private type NodeId        = String
  private type NextLinkLabel = Option[String]
  private type ActiveNode    = (NodeId, NextLinkLabel)
  private type PendingError  = (NodeId, WIOMeta.Error)

  case class RenderState(
      chart: MermaidFlowchart,
      idIdx: Int,
      activeNodes: Seq[ActiveNode] = Seq(),
      pendingErrors: Seq[PendingError],
      underRetrying: Boolean,
  ) {
    def addPendingError(from: NodeId, error: WIOMeta.Error): RenderState = copy(pendingErrors = pendingErrors.appended((from, error)))
    def addElements(els: Seq[MermaidElement]): RenderState               = copy(chart = chart.addElements(els))
  }
  private object RenderState {
    def initial(idIdx: Int): RenderState = RenderState(MermaidFlowchart(), idIdx, Seq(), Seq(), false)
  }

}
