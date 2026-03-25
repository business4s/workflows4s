package workflows4s.web.ui.components.instance

import cats.effect.IO
import tyrian.Html.*
import tyrian.{Cmd, Empty, Html}
import workflows4s.web.api.model.{Signal, WorkflowInstance}
import workflows4s.web.ui.components.instance.SignalsView.Msg

case class SignalsView(workflowInstance: WorkflowInstance, modal: Option[SignalModal]) {

  def view: Html[SignalsView.Msg] = div(
    div(cls := "buttons")(
      workflowInstance.expectedSignals.map(s => {
        val attrs =
          if s.requestSchema.isDefined then List(cls := "button is-small is-light", onClick(SignalsView.Msg.OpenModal(s)))
          else List(cls                              := "button is-small is-light", disabled(true))
        button(attrs*)(
          text(s.name),
        )
      })*,
    ),
    modal.map(_.view.map(SignalsView.Msg.ForDetails(_))).getOrElse(Empty),
  )

  def update(msg: SignalsView.Msg): (SignalsView, Cmd[IO, SignalsView.Msg]) = msg match {
    case Msg.OpenModal(signal) => (this.copy(modal = Some(SignalModal(workflowInstance, signal, true))), Cmd.None)
    case Msg.ForDetails(msg)   =>
      val (newThis, cmd) = this.modal.map(_.update(msg)) match {
        case Some((newCmp, innerCmd)) => this.copy(modal = Some(newCmp)) -> innerCmd.map(Msg.ForDetails(_))
        case None                     => this                            -> Cmd.None
      }
      msg match {
        case SignalModal.Msg.SignalSent => newThis -> Cmd.Batch(cmd, Cmd.Emit(Msg.RefreshInstance))
        case _                          => newThis -> cmd
      }
    case Msg.RefreshInstance   => (this, Cmd.None)
  }

}

object SignalsView {

  enum Msg {
    case OpenModal(signal: Signal)
    case ForDetails(msg: SignalModal.Msg)
    case RefreshInstance
  }

}
