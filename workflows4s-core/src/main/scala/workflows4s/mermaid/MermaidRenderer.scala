package workflows4s.mermaid

import cats.data.State
import cats.syntax.all.*
import workflows4s.wio.model.WIOModel

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
          activeNs <- cleanActiveNodes
          stepId   <- addStep(operationName.getOrElse(s"Handle ${signalName}"))
          _        <- addSignal(activeNs, stepId, signalName)
          _        <- error.traverse(addPendingError(stepId, _))
        } yield stepId.some
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
      case WIOModel.Loop(base, conditionName, exitBranchName, restartBranchName, onRestart) => ???
      case WIOModel.Fork(branches, name)                                                    => ???
      case WIOModel.Interruptible(base, trigger, handleFlow)                                =>
        for {
          (baseEnds, baseStart) <- addSubgraph(go(base))
          _                     <- go(trigger)
          _                     <- handleFlow.traverse(go)
          _                     <- State.modify[RenderState](s => s.copy(lastNodes = baseEnds))
        } yield baseStart
      case WIOModel.Timer(duration, name)                                                   => ???
    }

  type NodeId = String

  def cleanActiveNodes: State[RenderState, Seq[NodeId]] = State { s => s.copy(lastNodes = Seq()) -> s.lastNodes }

  def addNode(nodeF: NodeId => Node, active: Boolean): State[RenderState, NodeId] = State { s =>
    val nodeId = s"node${s.idIdx}"
    s.copy(
      chart = s.chart.addElement(nodeF(nodeId)),
      idIdx = s.idIdx + 1,
      lastNodes = if (active) s.lastNodes.appended(nodeId) else s.lastNodes,
    ) -> nodeId
  }
  def addStep(label: String, shape: Option[String] = None): State[RenderState, NodeId]                          = {
    for {
      prev <- cleanActiveNodes
      id   <- addNode(id => Node(id, label, shape), active = true)
      _    <- addLinks(prev, id)
    } yield id
  }

  def addSignal(from: Seq[NodeId], to: NodeId, name: String): State[RenderState, Unit]          = State { state =>
    val links = from.map(f => Link(f, to, s"fa:fa-envelope $name".some))
    (state.copy(chart = state.chart.addElements(links)), ())
  }
  def addError(from: NodeId, to: NodeId, err: WIOModel.Error): State[RenderState, Unit]         = State { state =>
    (
      state.copy(
        chart = state.chart
          .addLink(from, to, label = s"fa:fa-bolt ${err.name}".some),
        idIdx = state.idIdx + 1,
      ),
      (),
    )
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
  def addLink(from: NodeId, to: NodeId): State[RenderState, Unit]                               = State { state =>
    (state.copy(chart = state.chart.addLink(from, to)), ())
  }
  def addLinks(from: Seq[NodeId], to: NodeId): State[RenderState, Unit]                         = State { state =>
    (state.copy(chart = state.chart.addElements(from.map(f => Link(f, to)))), ())
  }
  def addSubgraph[T](subgraph: State[RenderState, T]): State[RenderState, (Seq[NodeId], T)]     = State { state =>
    val id                  = s"node${state.idIdx}"
    val (subState, subProc) = subgraph.run(RenderState.initial(state.idIdx + 1).copy(lastNodes = state.lastNodes)).value
    (
      state.copy(
        chart = state.chart.addSubgraph(id, " ")(_ => subState.chart),
        idIdx = state.idIdx + subState.idIdx + 1,
        lastNodes = Seq(id),
        pendingErrors = state.pendingErrors ++ subState.pendingErrors,
      ),
      (subState.lastNodes, subProc),
    )
  }

  case class RenderState(chart: MermaidFlowchart.Builder, idIdx: Int, lastNodes: Seq[NodeId] = Seq(), pendingErrors: Seq[(NodeId, WIOModel.Error)]) {
    def addNode(nodeF: NodeId => Node, active: Boolean): RenderState      = {
      val nodeId = s"node$idIdx"
      copy(chart = chart.addElement(nodeF(nodeId)), idIdx = idIdx + 1, lastNodes = if (active) lastNodes.appended(nodeId) else lastNodes)
    }
    def addPendingError(from: NodeId, error: WIOModel.Error): RenderState = copy(pendingErrors = pendingErrors.appended((from, error)))
  }
  object RenderState                                                                                                                                {
    def initial(idIdx: Int) = {
      RenderState(MermaidFlowchart.builder, idIdx, Seq(), Seq())
    }
  }

//  case class Process(startId: NodeId, endId: Seq[NodeId], errorIds: Seq[(NodeId, WIOModel.Error)])
}
