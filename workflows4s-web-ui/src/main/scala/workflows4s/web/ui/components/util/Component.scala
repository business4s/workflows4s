package workflows4s.web.ui.components.util

import cats.effect.IO
import tyrian.{Cmd, Html}

trait Component {
  type Self <: Component.Aux[Self, Msg]
  type Msg
  def update(msg: Msg): (Self, Cmd[IO, Msg])
  def view: Html[Msg]
}

object Component {
  type Aux[S, M] = Component { type Self = S; type Msg = M }
  type AuxM[M]   = Component { type Msg = M }

  type Msg[S <: Component] = S match {
    case AuxM[m] => m
  }
}
