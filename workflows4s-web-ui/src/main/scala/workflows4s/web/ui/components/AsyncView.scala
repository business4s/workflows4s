package workflows4s.web.ui.components

import cats.effect.IO
import tyrian.{Cmd, Html}
import workflows4s.web.ui.components.AsyncView.{Msg, State}
import workflows4s.web.ui.components.Component.Aux

trait Component {
  type Self <: Component.Aux[Self, Msg]
  type Msg
  def update(msg: Msg): (Self, Cmd[IO, Msg])
  def view: Html[Msg]
}

object Component {
  type Aux[S, M] = Component { type Self = S; type Msg = M }
}

case class AsyncView[Data, S <: Component.Aux[S, M], M](
    action: IO[Data],
    onFinish: Data => S,
    state: AsyncView.State[S, M],
) {

  def contentOpt: Option[S] = state match {
    case State.Initial()        => None
    case State.Loading()        => None
    case State.Ready(component) => Some(component)
    case State.Failed(failed)   => None
  }

  def view: Html[AsyncView.Msg[Data, M]] = {
    state match {
      case State.Initial()        => ReusableViews.loadingSpinner("???")
      case State.Loading()        => ReusableViews.loadingSpinner("Loading workflow definitions")
      case State.Ready(component) => component.view.map(Msg.Propagate(_))
      case State.Failed(failed)   => ReusableViews.errorView(failed.getMessage)
    }
  }

  def update(msg: AsyncView.Msg[Data, M]): (AsyncView[Data, S, M], Cmd[IO, AsyncView.Msg[Data, M]]) =
    state match {
      case State.Initial()        =>
        msg match {
          case Msg.Start => this.copy(state = AsyncView.State.Loading()) -> Cmd.Run(action.attempt, Msg.Finished.apply)
          case _         => ???
        }
      case State.Loading()        =>
        msg match {
          case Msg.Finished(result) =>
            result match {
              case Left(error) =>
                this.copy(state = State.Failed(error)) -> Cmd.None
              case Right(data) =>
                this.copy(state = State.Ready(onFinish(data))) -> Cmd.None
            }
          case _                    => ???
        }
      case State.Ready(component) =>
        msg match {
          case Msg.Propagate(msg) =>
            val (newComp, cmd) = component.update(msg)
            this.copy(state = State.Ready(newComp)) -> cmd.map(Msg.Propagate(_))
          case _                  => ???
        }
      case State.Failed(_)        =>
        msg match {
          case Msg.Start => this.copy(state = AsyncView.State.Loading()) -> Cmd.Run(action.attempt, Msg.Finished.apply)
          case _         => ???
        }
    }

}

object AsyncView {

  def empty[Data, S <: Component.Aux[S, M], M](action: IO[Data], onFinish: Data => S) = {
    AsyncView[Data, S, M](action, onFinish, State.Initial()) -> Cmd.Emit(Msg.Start)
  }

  enum State[S, M] {
    case Initial()
    case Loading()
    case Ready(component: S)
    case Failed(failed: Throwable)
  }

  enum Msg[+Data, +SubMsg] {
    case Start
    case Finished(result: Either[Throwable, Data])
    case Propagate(msg: SubMsg)
  }

}
