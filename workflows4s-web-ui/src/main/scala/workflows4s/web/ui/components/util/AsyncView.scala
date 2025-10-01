package workflows4s.web.ui.components.util

import cats.effect.IO
import tyrian.{Cmd, Html}
import workflows4s.web.ui.components.ReusableViews
import workflows4s.web.ui.components.util.AsyncView.{Msg, State}

import scala.annotation.unused

case class AsyncView[S <: Component.Aux[S, M], M](
    action: IO[(S, Cmd[IO, M])],
    state: AsyncView.State[S, M],
    @unused x: Int = 1, // https://github.com/scala-js/scala-js/issues/5235
) {

  def refresh: Cmd.Emit[Msg.Start[S]] = AsyncView.startCmd[S]

  def isLoading: Boolean = state match {
    case State.Initial() => false
    case State.Loading() => true
    case State.Ready(_)  => false
    case State.Failed(_) => false
  }

  def view: Html[AsyncView.Msg[S]] = {
    state match {
      case State.Initial()        => ReusableViews.loadingSpinner("???")
      case State.Loading()        => ReusableViews.loadingSpinner("Loading workflow definitions")
      case State.Ready(component) => component.view.map(Msg.Propagate(_))
      case State.Failed(failed)   => ReusableViews.errorView(failed.getMessage)
    }
  }

  def update(msg: AsyncView.Msg[S]): (AsyncView[S, M], Cmd[IO, AsyncView.Msg[S]]) = {
    def restart: (AsyncView[S, M], Cmd[IO, AsyncView.Msg[S]]) = this.copy(state = AsyncView.State.Loading()) -> Cmd.Run(action.attempt, Msg.Finished.apply)
    state match {
      case State.Initial()        =>
        msg match {
          case Msg.Start() => restart
          case _           => ???
        }
      case State.Loading()        =>
        msg match {
          case Msg.Finished(result) =>
            result match {
              case Left(error)            =>
                this.copy(state = State.Failed(error)) -> Cmd.None
              case Right((newState, cmd)) =>
                this.copy(state = State.Ready(newState)) -> cmd.map(Msg.Propagate(_))
            }
          case _                    => ???
        }
      case State.Ready(component) =>
        msg match {
          case Msg.Start()        => restart
          case Msg.Propagate(msg) =>
            val (newComp, cmd) = component.update(msg)
            this.copy(state = State.Ready(newComp)) -> cmd.map(Msg.Propagate(_))
          case _                  => ???
        }
      case State.Failed(_)        =>
        msg match {
          case Msg.Start() => restart
          case _           => ???
        }
    }
  }

}

object AsyncView {

  type For[S <: Component.Aux[S, Component.Msg[S]]] = AsyncView[S, Component.Msg[S]]

  def empty_[Data, S <: Component.Aux[S, M], M](action: IO[Data], onFinish: Data => S): (AsyncView[S, M], Cmd.Emit[Msg.Start[S]])              = {
    AsyncView[S, M](action.map(onFinish).map(x => (x, Cmd.None)), State.Initial()) -> startCmd
  }
  def empty[Data, S <: Component.Aux[S, M], M](action: IO[Data], onFinish: Data => (S, Cmd[IO, M])): (AsyncView[S, M], Cmd.Emit[Msg.Start[S]]) = {
    AsyncView[S, M](action.map(onFinish), State.Initial()) -> startCmd
  }

  def startCmd[S <: Component]: Cmd.Emit[Msg.Start[S]] = Cmd.Emit(Msg.Start())

  enum State[S <: Component.Aux[S, M], M] {
    case Initial()
    case Loading()
    case Ready(component: S)
    case Failed(failed: Throwable)
  }

  enum Msg[S <: Component] {
    case Start()
    case Finished(result: Either[Throwable, (S, Cmd[IO, Component.Msg[S]])])
    case Propagate(msg: Component.Msg[S])
  }

}
