package workflows4s.mermaid

import cats.data.State
import cats.syntax.all.*
import workflows4s.wio.model.WIOModel

import java.time.Duration

object MermaidRenderer {

  def renderWorkflow(model: WIOModel): MermaidFlowchart = {
    (addNode(id => Node(id, "Start", shape = "circle".some), active = true) *>
      render(model))
      .run(RenderState.initial(0))
      .value
      ._1
      .chart
  }

  private def render(model: WIOModel): State[RenderState, Option[NodeId]] =
    model match {
      case WIOModel.Sequence(steps)                                                         =>
        for {
          subSteps <- steps.traverse(render)
        } yield subSteps.flatten.headOption
      case WIOModel.Dynamic(name, error)                                                    =>
        for {
          stepId <- addStep(name.getOrElse("@dynamic"))
          _      <- error.traverse(addPendingError(stepId, _))
        } yield stepId.some
      case WIOModel.RunIO(error, name)                                                      =>
        for {
          stepId <- addStep(name.getOrElse("@computation"))
          _      <- error.traverse(addPendingError(stepId, _))
        } yield stepId.some
      case WIOModel.HandleSignal(signalName, error, operationName)                          =>
        for {
          signalId <- addStepGeneral(id => Node(id, s"fa:fa-envelope $signalName", shape = eventShape.some))
          stepId   <- addStep(operationName.getOrElse(s"Handle ${signalName}"))
          _        <- error.traverse(addPendingError(stepId, _))
        } yield signalId.some
      case WIOModel.HandleError(base, handler, error)                                       =>
        // generalized error label is unused as we connect specific errors directly to the handler
        for {
          baseStart    <- render(base)
          baseEnds     <- cleanActiveNodes
          errors       <- cleanPendingErrors
          handlerStart <- render(handler)
          _            <- handleErrors(errors, handlerStart.get) // TODO better error handling or more typesafety?
          _            <- State.modify[RenderState] { s => s.copy(activeNodes = baseEnds ++ s.activeNodes) }
        } yield baseStart
      case WIOModel.End                                                                     =>
        addStep("End", shape = "circle".some).map(_.some)
      case WIOModel.Pure(name, error)                                                       =>
        if (name.isDefined || error.isDefined) {
          for {
            stepId <- addStep(name.getOrElse("@computation"))
            _      <- error.traverse(addPendingError(stepId, _))
          } yield stepId.some
        } else State.pure(None)
      case WIOModel.Loop(base, conditionName, exitBranchName, restartBranchName, onRestart) =>
        for {
          baseStart     <- render(base)
          conditionNode <- addStep(conditionName.getOrElse(" "), shape = conditionShape.some)
          _             <- setNextLinkLabel(restartBranchName)
          _             <- onRestart.flatTraverse(render)
          ends          <- cleanActiveNodes
          _             <- addLinks(ends, baseStart.get)
          _             <- setActiveNodes(Seq(conditionNode -> exitBranchName))
        } yield baseStart
      case WIOModel.Fork(branches, name)                                                    => {
        def renderBranch(branch: WIOModel.Branch, conditionNode: NodeId): State[RenderState, Vector[(NodeId, NextLinkLabel)]] = {
          for {
            _           <- cleanActiveNodes
            branchStart <- render(branch.logic)
            _           <- branchStart.traverse(addLink(conditionNode, _, label = branch.label))
            branchEnds  <- cleanActiveNodes
          } yield branchEnds.toVector
        }
        for {
          conditionNode <- addStep(name.getOrElse(" "), shape = conditionShape.some)
          ends          <- branches.flatTraverse(renderBranch(_, conditionNode))
          _             <- setActiveNodes(ends)
        } yield conditionNode.some
      }
      case WIOModel.Interruptible(base, trigger, handleFlow)                                =>
        for {
          (baseEnds, baseStart) <- addSubgraph(render(base))
          _                     <- render(trigger)
          _                     <- handleFlow.traverse(render)
          _                     <- State.modify[RenderState](s => s.copy(activeNodes = s.activeNodes ++ baseEnds))
        } yield baseStart
      case WIOModel.Timer(duration, name)                                                   =>
        val durationStr = duration.map(humanReadableDuration).getOrElse("dynamic")
        val label       = s"${name.getOrElse("")} ($durationStr)"
        for {
          stepId <- addStepGeneral(id => Node(id, s"fa:fa-clock ${label}", shape = eventShape.some))
        } yield stepId.some
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
  private def addStep(label: String, shape: Option[String] = None): State[RenderState, NodeId]                                    = {
    addStepGeneral(id => Node(id, label, shape))
  }
  private def addStepGeneral(createElem: NodeId => MermaidElement): State[RenderState, NodeId]                                    = {
    for {
      prev <- cleanActiveNodes
      id   <- addNode(createElem, active = true)
      _    <- addLinks(prev, id)
    } yield id
  }

  private def addPendingError(from: NodeId, err: WIOModel.Error): State[RenderState, Unit] =
    State.modify(_.addPendingError(from, err))

  private def handleErrors(errors: Seq[(NodeId, WIOModel.Error)], to: NodeId): State[RenderState, Unit] = State.modify { state =>
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
  private type PendingError  = (NodeId, WIOModel.Error)

  case class RenderState(
      chart: MermaidFlowchart,
      idIdx: Int,
      activeNodes: Seq[ActiveNode] = Seq(),
      pendingErrors: Seq[PendingError],
  ) {
    def addPendingError(from: NodeId, error: WIOModel.Error): RenderState = copy(pendingErrors = pendingErrors.appended((from, error)))
    def addElements(els: Seq[MermaidElement]): RenderState                = copy(chart = chart.addElements(els))
  }
  private object RenderState {
    def initial(idIdx: Int): RenderState = RenderState(MermaidFlowchart(), idIdx, Seq(), Seq())
  }

  // TODO commonize with bpmn renderer
  def humanReadableDuration(duration: Duration): String = duration.toString.substring(2).replaceAll("(\\d[HMS])(?!$)", "$1 ").toLowerCase

}
