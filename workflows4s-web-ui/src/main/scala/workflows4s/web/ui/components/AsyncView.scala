package workflows4s.web.ui.components

import cats.effect.IO
import tyrian.{Cmd, Html}
import workflows4s.web.ui.components.AsyncView.State.Ready
import workflows4s.web.ui.components.AsyncView.{Msg, State}

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
    onFinish: Either[Throwable, Data] => Component.Aux[S, M],
    state: AsyncView.State[S, M],
) {

  def update(msg: AsyncView.Msg[Data, M]): (AsyncView[Data, S, M], Cmd[IO, AsyncView.Msg[Data, M]]) =
    state match {
      case State.Initial()        =>
        msg match {
          case Msg.Start => this.copy(state = AsyncView.State.Loading()) -> Cmd.Run(action.attempt, Msg.Finished.apply)
          case _         => ???
        }
      case State.Loading()        =>
        msg match {
          case Msg.Finished(result) => this.copy(state = State.Ready(onFinish(result))) -> Cmd.None
          case _                    => ???
        }
      case State.Ready(component) =>
        msg match {
          case Msg.Propagate(msg) =>
            val (newComp, cmd) = component.update(msg)
            this.copy(state = State.Ready(newComp)) -> cmd.map(Msg.Propagate(_))
          case _                  => ???
        }
    }

}

object AsyncView {

  enum State[S, M] {
    case Initial()
    case Loading()
    case Ready(component: Component.Aux[S, M])
  }

  enum Msg[+Data, +SubMsg] {
    case Start
    case Finished(result: Either[Throwable, Data])
    case Propagate(msg: SubMsg)
  }

}
