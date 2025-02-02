package workflows4s.mermaid

import cats.data.State
import cats.syntax.all.*
import workflows4s.wio.model.{WIOExecutionProgress, WIOMeta}

import java.time.Duration

object MermaidRenderer {

  def renderWorkflow(model: WIOExecutionProgress[?]): MermaidFlowchart = {
    (addNode(id => Node(id, "Start", shape = "circle".some, clazz = executedClass.some), active = true) *>
      render(model))
      .run(RenderState.initial(0))
      .value
      ._1
      .chart
      .addElement(styles)
  }

  val executedClass = "executed"
  val styles        = ClassDef(executedClass, "fill:#0e0")

  private def render(model: WIOExecutionProgress[?]): State[RenderState, Option[NodeId]] = {
    def addStep(label: String, shape: Option[String] = None): State[RenderState, NodeId] = {
      addStepGeneral(id => Node(id, label, shape, clazz = if (model.result.isDefined) executedClass.some else None))
    }
    model match {
      case WIOExecutionProgress.Sequence(steps)                                                            =>
        for {
          subSteps <- steps.traverse(render)
        } yield subSteps.flatten.headOption
      case WIOExecutionProgress.Dynamic(meta)                                                              =>
        for {
          stepId <- addStep("@dynamic")
          _      <- meta.error.traverse(addPendingError(stepId, _))
        } yield stepId.some
      case WIOExecutionProgress.RunIO(meta, _)                                                             =>
        for {
          stepId <- addStep(meta.name.getOrElse("@computation"))
          _      <- meta.error.traverse(addPendingError(stepId, _))
        } yield stepId.some
      case WIOExecutionProgress.HandleSignal(meta, _)                                                      =>
        for {
          signalId <- addStepGeneral(id =>
                        Node(id, s"fa:fa-envelope ${meta.signalName}", shape = eventShape.some, clazz = if (model.result.isDefined) executedClass.some else None),
                      )
          stepId   <- addStep(meta.operationName.getOrElse(s"Handle ${meta.signalName}"))
          _        <- meta.error.traverse(addPendingError(stepId, _))
        } yield signalId.some
      case WIOExecutionProgress.HandleError(base, handler, error, _)                                       =>
        // generalized error label is unused as we connect specific errors directly to the handler
        for {
          baseStart    <- render(base)
          baseEnds     <- cleanActiveNodes
          errors       <- cleanPendingErrors
          handlerStart <- render(handler)
          _            <- handleErrors(errors, handlerStart.get) // TODO better error handling or more typesafety?
          _            <- State.modify[RenderState] { s => s.copy(activeNodes = baseEnds ++ s.activeNodes) }
        } yield baseStart
      case WIOExecutionProgress.End(_)                                                                     =>
        addStep("End", shape = "circle".some).map(_.some)
      case WIOExecutionProgress.Pure(meta, _)                                                       =>
        if (meta.name.isDefined || meta.error.isDefined) {
          for {
            stepId <- addStep(meta.name.getOrElse("@computation"))
            _      <- meta.error.traverse(addPendingError(stepId, _))
          } yield stepId.some
        } else State.pure(None)
      case WIOExecutionProgress.Loop(base, onRestart, meta, history) =>
        // TODO shoudl render history if present
        for {
          baseStart     <- render(base.toEmptyProgress)
          conditionNode <- addStep(meta.conditionName.getOrElse(" "), shape = conditionShape.some)
          _             <- setNextLinkLabel(meta.restartBranchName)
          _             <- onRestart.flatTraverse(x => render(x.toEmptyProgress))
          ends          <- cleanActiveNodes
          _             <- addLinks(ends, baseStart.get)
          _             <- setActiveNodes(Seq(conditionNode -> meta.exitBranchName))
        } yield baseStart
      case WIOExecutionProgress.Fork(branches, meta, _)                                                    => {
        def renderBranch(
            branch: WIOExecutionProgress[?],
            meta: WIOMeta.Branch,
            conditionNode: NodeId,
        ): State[RenderState, Vector[(NodeId, NextLinkLabel)]] = {
          for {
            _           <- cleanActiveNodes
            branchStart <- render(branch)
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
      case WIOExecutionProgress.Interruptible(base, trigger, handleFlow, _)                                =>
        for {
          (baseEnds, baseStart) <- addSubgraph(render(base))
          _                     <- render(trigger)
          _                     <- handleFlow.traverse(render)
          _                     <- State.modify[RenderState](s => s.copy(activeNodes = s.activeNodes ++ baseEnds))
        } yield baseStart
      case WIOExecutionProgress.Timer(meta, _)                                                             =>
        val durationStr = meta.duration.map(humanReadableDuration).getOrElse("dynamic")
        val label       = s"${meta.name.getOrElse("")} ($durationStr)"
        for {
          stepId <-
            addStepGeneral(id =>
              Node(id, s"fa:fa-clock ${label}", shape = eventShape.some, clazz = if (model.result.isDefined) executedClass.some else None),
            )
        } yield stepId.some
    }
  }

  private val eventShape     = "stadium"
  private val conditionShape = "hex"

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

  private def handleErrors(errors: Seq[(NodeId, WIOMeta.Error)], to: NodeId): State[RenderState, Unit] = State.modify { state =>
    val links = errors.map(pendingErr => Link(pendingErr._1, to, s"fa:fa-bolt ${pendingErr._2.name}".some, midfix = "."))
    state.addElements(links)
  }
  private def addLink(from: NodeId, to: NodeId, label: Option[String] = None): State[RenderState, Unit] =
    addLinks(Seq((from, label)), to)
  private def addLinks(from: Seq[(NodeId, NextLinkLabel)], to: NodeId): State[RenderState, Unit]        =
    State.modify(_.addElements(from.map(f => Link(f._1, to, f._2))))

  private def addSubgraph[T](subgraph: State[RenderState, T]): State[RenderState, (Seq[ActiveNode], T)] = State { state =>
    val id                  = s"node${state.idIdx}"
    val (subState, subProc) = subgraph.run(RenderState.initial(state.idIdx + 1).copy(activeNodes = state.activeNodes)).value
    (
      state.copy(
        chart = state.chart.addElement(Subgraph(id, " ", subState.chart.elements)),
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
    def addElements(els: Seq[MermaidElement]): RenderState                = copy(chart = chart.addElements(els))
  }
  private object RenderState {
    def initial(idIdx: Int): RenderState = RenderState(MermaidFlowchart(), idIdx, Seq(), Seq())
  }

  // TODO commonize with bpmn renderer
  def humanReadableDuration(duration: Duration): String = duration.toString.substring(2).replaceAll("(\\d[HMS])(?!$)", "$1 ").toLowerCase

}
