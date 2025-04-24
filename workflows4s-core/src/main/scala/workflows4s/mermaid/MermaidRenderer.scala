package workflows4s.mermaid

import cats.data.State
import cats.syntax.all.*
import workflows4s.RenderUtils
import workflows4s.wio.model.{WIOExecutionProgress, WIOMeta}

import java.time.Duration

object MermaidRenderer {

  def renderWorkflow(model: WIOExecutionProgress[?], showTechnical: Boolean = false): MermaidFlowchart = {
    (addNode(
      id => Node(id, "Start", shape = "circle".some, clazz = if (hasStarted(model)) executedClass.some else None),
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

  private def render(model: WIOExecutionProgress[?], showTechnical: Boolean = false): State[RenderState, Option[NodeId]] = {
    def go(model: WIOExecutionProgress[?]): State[RenderState, Option[NodeId]] = {
      def addStep(label: String, shape: Option[String] = None): State[RenderState, NodeId] = {
        addStepGeneral(id => Node(id, label, shape, clazz = if (model.isExecuted) executedClass.some else None))
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
            _      <- meta.error.traverse(addPendingError(stepId, _))
          } yield stepId.some
        case WIOExecutionProgress.HandleSignal(meta, _)                       =>
          for {
            signalId <- addStepGeneral(id =>
                          Node(
                            id,
                            s"fa:fa-envelope ${meta.signalName}",
                            shape = eventShape.some,
                            clazz = if (model.isExecuted) executedClass.some else None,
                          ),
                        )
            stepId   <- addStep(meta.operationName.getOrElse(s"Handle ${meta.signalName}"))
            _        <- meta.error.traverse(addPendingError(stepId, _))
          } yield signalId.some
        case WIOExecutionProgress.HandleError(base, handler, error, _)        =>
          // generalized error label is unused as we connect specific errors directly to the handler
          for {
            baseStart    <- go(base)
            baseEnds     <- cleanActiveNodes
            errors       <- cleanPendingErrors
            handlerStart <- go(handler)
            _            <- handleErrors(errors, handlerStart.get) // TODO better error handling or more typesafety?
            _            <- State.modify[RenderState] { s => s.copy(activeNodes = baseEnds ++ s.activeNodes) }
          } yield baseStart
        case WIOExecutionProgress.End(_)                                      =>
          addStep("End", shape = "circle".some).map(_.some)
        case WIOExecutionProgress.Pure(meta, _)                               =>
          if (meta.name.isDefined || meta.error.isDefined) {
            for {
              stepId <- addStep(meta.name.getOrElse("@computation"))
              _      <- meta.error.traverse(addPendingError(stepId, _))
            } yield stepId.some
          } else State.pure(None)
        case WIOExecutionProgress.Loop(base, onRestart, meta, history)        =>
          // history contains `current` and this is prefilled on creation,
          // hence empty nodes have history of 1 with not started node
          if (history.size > 1 || history.lastOption.exists(hasStarted)) {
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
            (baseEnds, baseStart) <- addSubgraph(go(base))
            _                     <- go(trigger)
            _                     <- handleFlow.traverse(h => go(h))
            _                     <- State.modify[RenderState](s => s.copy(activeNodes = s.activeNodes ++ baseEnds))
          } yield baseStart
        case WIOExecutionProgress.Timer(meta, _)                              =>
          val durationStr = meta.duration.map(RenderUtils.humanReadableDuration).getOrElse("dynamic")
          val label       = s"${meta.name.getOrElse("")} ($durationStr)"
          for {
            stepId <-
              addStepGeneral(id =>
                Node(id, s"fa:fa-clock ${label}", shape = eventShape.some, clazz = if (model.isExecuted) executedClass.some else None),
              )
          } yield stepId.some
        case WIOExecutionProgress.Parallel(elems, _)                          =>
          for {
            forkId <- addStepGeneral(id => Node(id, "", shape = forkShape.some, clazz = if (hasStarted(model)) executedClass.some else None))
            ends   <- elems.toList.flatTraverse(element =>
                        for {
                          _    <- setActiveNodes(Seq((forkId, None)))
                          _    <- go(element)
                          ends <- cleanActiveNodes
                        } yield ends.toList,
                      )
            _      <- setActiveNodes(ends)
            endId  <- addStepGeneral(id => Node(id, "", shape = forkShape.some, clazz = if (model.isExecuted) executedClass.some else None))
          } yield forkId.some

        case WIOExecutionProgress.Checkpoint(base, result) =>
          if (showTechnical) {
            for {
              (baseEnds, baseStart) <-
                addSubgraph(go(base), "Checkpoint", Some(if (model.isExecuted) checkpointExecutedClass else checkpointClass))
              _                     <- State.modify[RenderState](s => s.copy(activeNodes = baseEnds))
            } yield baseStart
          } else go(base)

        case WIOExecutionProgress.Recovery(result) =>
          // This is a recovery-only checkpoint (created with WIO.recover)
          if (showTechnical)
            addStepGeneral(id =>
              Node(id, "fa:fa-wrench State Recovery", shape = "hexagon".some, clazz = if (model.isExecuted) executedClass.some else None),
            ).map(_.some)
          else State.pure(None)
      }
    }

    go(model)
  }

  private def hasStarted(model: WIOExecutionProgress[?]): Boolean = model match {
    case WIOExecutionProgress.Sequence(steps)                               => hasStarted(steps.head)
    case WIOExecutionProgress.Dynamic(meta)                                 => false
    case WIOExecutionProgress.RunIO(meta, result)                           => result.isDefined
    case WIOExecutionProgress.HandleSignal(meta, result)                    => result.isDefined
    case WIOExecutionProgress.HandleError(base, handler, meta, result)      => hasStarted(base) || hasStarted(handler)
    case WIOExecutionProgress.End(result)                                   => result.isDefined
    case WIOExecutionProgress.Pure(meta, result)                            => result.isDefined
    case WIOExecutionProgress.Loop(base, onRestart, meta, history)          => history.nonEmpty
    case WIOExecutionProgress.Fork(branches, meta, selected)                => selected.isDefined
    case WIOExecutionProgress.Interruptible(base, trigger, handler, result) => hasStarted(base) || hasStarted(trigger)
    case WIOExecutionProgress.Timer(meta, result)                           => result.isDefined
    case WIOExecutionProgress.Parallel(elems, _)                            => elems.exists(hasStarted)
    case WIOExecutionProgress.Checkpoint(base, result)                      => result.isDefined || hasStarted(base)
    case WIOExecutionProgress.Recovery(result)                              => result.isDefined
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

  private def addNode(nodeF: NodeId => MermaidElement, active: Boolean, label: Option[String] = None): State[RenderState, NodeId] = State { s =>
    val nodeId = s"node${s.idIdx}"
    s.copy(
      chart = s.chart.addElement(nodeF(nodeId)),
      idIdx = s.idIdx + 1,
      activeNodes = if (active) s.activeNodes.appended(nodeId -> label) else s.activeNodes,
    ) -> nodeId
  }
  private def addStepGeneral(createElem: NodeId => MermaidElement): State[RenderState, NodeId]                                    = {
    for {
      prev <- cleanActiveNodes
      id   <- addNode(createElem, active = true)
      _    <- addLinks(prev, id)
    } yield id
  }

  private def addPendingError(from: NodeId, err: WIOMeta.Error): State[RenderState, Unit] =
    State.modify(_.addPendingError(from, err))

  private def handleErrors(errors: Seq[(NodeId, WIOMeta.Error)], to: NodeId): State[RenderState, Unit]  = State.modify { state =>
    val links = errors.map(pendingErr => Link(pendingErr._1, to, s"fa:fa-bolt ${pendingErr._2.name}".some, midfix = "."))
    state.addElements(links)
  }
  private def addLink(from: NodeId, to: NodeId, label: Option[String] = None): State[RenderState, Unit] =
    addLinks(Seq((from, label)), to)
  private def addLinks(from: Seq[(NodeId, NextLinkLabel)], to: NodeId): State[RenderState, Unit]        =
    State.modify(_.addElements(from.map(f => Link(f._1, to, f._2))))

  private def addSubgraph[T](
      subgraph: State[RenderState, T],
      title: String = " ",
      clazz: Option[String] = None,
  ): State[RenderState, (Seq[ActiveNode], T)] = State { state =>
    val id                  = s"node${state.idIdx}"
    val (subState, subProc) = subgraph.run(RenderState.initial(state.idIdx + 1).copy(activeNodes = state.activeNodes)).value
    (
      state.copy(
        chart = state.chart.addElement(Subgraph(id, title, subState.chart.elements, clazz)),
        idIdx = state.idIdx + subState.idIdx + 1,
        activeNodes = Seq((id, None)),
        pendingErrors = state.pendingErrors ++ subState.pendingErrors,
      ),
      (subState.activeNodes, subProc),
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
  ) {
    def addPendingError(from: NodeId, error: WIOMeta.Error): RenderState = copy(pendingErrors = pendingErrors.appended((from, error)))
    def addElements(els: Seq[MermaidElement]): RenderState               = copy(chart = chart.addElements(els))
  }
  private object RenderState {
    def initial(idIdx: Int): RenderState = RenderState(MermaidFlowchart(), idIdx, Seq(), Seq())
  }

}
