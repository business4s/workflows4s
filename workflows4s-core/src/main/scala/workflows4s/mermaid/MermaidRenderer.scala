package workflows4s.mermaid

import cats.data.State
import cats.syntax.all.*
import workflows4s.wio.model.WIOModel

import java.time.Duration

object MermaidRenderer {

  def renderWorkflow(model: WIOModel): MermaidFlowchart = {
    (addNode(id => Node(id, "Start", shape = "circle".some), active = true) *>
      go(model))
      .run(RenderState.initial(0))
      .value
      ._1
      .chart
      .build
  }

  private def go(model: WIOModel): State[RenderState, Option[NodeId]] =
    model match {
      case WIOModel.Sequence(steps)                                                         =>
        for {
          subSteps <- steps.traverse(go)
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
          signalId <- addStep1(id => Node(id, s"fa:fa-envelope $signalName", shape = "stadium".some))
          stepId   <- addStep(operationName.getOrElse(s"Handle ${signalName}"))
          _        <- error.traverse(addPendingError(stepId, _))
        } yield signalId.some
      case WIOModel.HandleError(base, handler, error)                                       =>
        // TODO error unused?
        for {
          baseStart    <- go(base)
          baseEnds     <- cleanActiveNodes
          errors       <- State[RenderState, Seq[(NodeId, WIOModel.Error)]](s => s.copy(lastNodes = Seq(), pendingErrors = Seq()) -> s.pendingErrors)
          handlerStart <- go(handler)
          _            <- handleErrors(errors, handlerStart.get) // TODO better error handling or more typesafety?
          _            <- State.modify[RenderState] { s => s.copy(lastNodes = baseEnds ++ s.lastNodes) }
        } yield baseStart
      case WIOModel.End                                                                     =>
        for {
          stepId <- addStep("End", shape = "circle".some)
        } yield stepId.some
      case WIOModel.Pure(name, error)                                                       =>
        if (name.isDefined || error.isDefined) {
          for {
            stepId <- addStep(name.getOrElse("@computation"))
            _      <- error.traverse(addPendingError(stepId, _))
          } yield stepId.some
        } else State.pure(None)
      case WIOModel.Loop(base, conditionName, exitBranchName, restartBranchName, onRestart) =>
        for {
          baseStart     <- go(base)
          conditionNode <- addStep(conditionName.getOrElse(" "), shape = "hex".some)
          _             <- setNextLinkLabel(restartBranchName)
          _             <- onRestart.flatTraverse(go)
          ends          <- cleanActiveNodes
          _             <- addLinks(ends, baseStart.get)
          _             <- setActiveNodes(Seq(conditionNode -> exitBranchName))
        } yield baseStart
      case WIOModel.Fork(branches, name)                                                    => {
        def renderBranch(branch: WIOModel.Branch, conditionNode: NodeId): State[RenderState, Vector[(NodeId, NextLinkLabel)]] = {
          for {
            _           <- cleanActiveNodes
            branchStart <- go(branch.logic)
            _           <- branchStart.traverse(addLink(conditionNode, _, label = branch.label))
            branchEnds  <- cleanActiveNodes
          } yield branchEnds.toVector
        }
        for {
          conditionNode <- addStep(name.getOrElse(" "), shape = "hex".some)
          ends          <- branches.flatTraverse(renderBranch(_, conditionNode))
          _             <- setActiveNodes(ends)
        } yield conditionNode.some
      }
      case WIOModel.Interruptible(base, trigger, handleFlow)                                =>
        for {
          (baseEnds, baseStart) <- addSubgraph(go(base))
          _                     <- go(trigger)
          _                     <- handleFlow.traverse(go)
          _                     <- State.modify[RenderState](s => s.copy(lastNodes = s.lastNodes ++ baseEnds))
        } yield baseStart
      case WIOModel.Timer(duration, name)                                                   =>
        val durationStr = duration.map(humanReadableDuration).getOrElse("dynamic duration")
        val label       = s"${name.getOrElse("")} ($durationStr)"
        for {
          stepId <- addStep1(id => Node(id, s"fa:fa-clock ${label}", shape = "stadium".some))
        } yield stepId.some
    }

  type NodeId        = String
  type NextLinkLabel = Option[String]
  type ActiveNode    = (NodeId, NextLinkLabel)

  def cleanActiveNodes: State[RenderState, Seq[ActiveNode]]            = State { s => s.copy(lastNodes = Seq()) -> s.lastNodes }
  def setActiveNodes(nodes: Seq[ActiveNode]): State[RenderState, Unit] = State.modify { s => s.copy(lastNodes = nodes) }
  def setNextLinkLabel(label: NextLinkLabel): State[RenderState, Unit] = State.modify { s => s.copy(lastNodes = s.lastNodes.map(_._1 -> label)) }

  def addNode(nodeF: NodeId => MermaidElement, active: Boolean, label: Option[String] = None): State[RenderState, NodeId] = State { s =>
    val nodeId = s"node${s.idIdx}"
    s.copy(
      chart = s.chart.addElement(nodeF(nodeId)),
      idIdx = s.idIdx + 1,
      lastNodes = if (active) s.lastNodes.appended(nodeId -> label) else s.lastNodes,
    ) -> nodeId
  }
  def addStep(label: String, shape: Option[String] = None): State[RenderState, NodeId]                                    = {
    for {
      prev <- cleanActiveNodes
      id   <- addNode(id => Node(id, label, shape), active = true)
      _    <- addLinks(prev, id)
    } yield id
  }
  def addStep1(createElem: NodeId => MermaidElement): State[RenderState, NodeId]                                          = {
    for {
      prev <- cleanActiveNodes
      id   <- addNode(createElem, active = true)
      _    <- addLinks(prev, id)
    } yield id
  }

  def addPendingError(from: NodeId, err: WIOModel.Error): State[RenderState, Unit]              = State { state =>
    (
      state.addPendingError(from, err),
      (),
    )
  }
  def handleErrors(errors: Seq[(NodeId, WIOModel.Error)], to: NodeId): State[RenderState, Unit] = State { state =>
    val links = errors.map(pendingErr => Link(pendingErr._1, to, s"fa:fa-bolt ${pendingErr._2.name}".some, midfix = "."))
    state.copy(chart = state.chart.addElements(links)) -> ()
  }
  def addLink(from: NodeId, to: NodeId, label: Option[String] = None): State[RenderState, Unit] = State { state =>
    (state.copy(chart = state.chart.addLink(from, to, label = label)), ())
  }
  def addLinks(from: Seq[(NodeId, NextLinkLabel)], to: NodeId): State[RenderState, Unit]        = State { state =>
    (state.copy(chart = state.chart.addElements(from.map(f => Link(f._1, to, f._2)))), ())
  }
  def addSubgraph[T](subgraph: State[RenderState, T]): State[RenderState, (Seq[ActiveNode], T)] = State { state =>
    val id                  = s"node${state.idIdx}"
    val (subState, subProc) = subgraph.run(RenderState.initial(state.idIdx + 1).copy(lastNodes = state.lastNodes)).value
    (
      state.copy(
        chart = state.chart.addSubgraph(id, " ")(_ => subState.chart),
        idIdx = state.idIdx + subState.idIdx + 1,
        lastNodes = Seq((id, None)),
        pendingErrors = state.pendingErrors ++ subState.pendingErrors,
      ),
      (subState.lastNodes, subProc),
    )
  }

  case class RenderState(
      chart: MermaidFlowchart.Builder,
      idIdx: Int,
      lastNodes: Seq[ActiveNode] = Seq(),
      pendingErrors: Seq[(NodeId, WIOModel.Error)],
  ) {
    def addPendingError(from: NodeId, error: WIOModel.Error): RenderState = copy(pendingErrors = pendingErrors.appended((from, error)))
  }
  object RenderState {
    def initial(idIdx: Int) = {
      RenderState(MermaidFlowchart.builder, idIdx, Seq(), Seq())
    }
  }

  // TODO commonize with bpmn renderer
  def humanReadableDuration(duration: Duration): String = duration.toString.substring(2).replaceAll("(\\d[HMS])(?!$)", "$1 ").toLowerCase

}
