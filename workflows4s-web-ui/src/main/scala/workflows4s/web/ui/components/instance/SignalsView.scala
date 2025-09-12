package workflows4s.web.ui.components.instance

import cats.effect.IO
import tyrian.Html.*
import tyrian.{Cmd, Empty, Html}
import workflows4s.web.api.model.{Signal, WorkflowInstance}
import workflows4s.web.ui.components.instance.SignalsView.Msg

case class SignalsView(workflowInstance: WorkflowInstance, modal: Option[SignalModal]) {

  def view: Html[SignalsView.Msg] = div(
    ul(
      workflowInstance.expectedSignals.map(s =>
        li(
          text(s.name),
          if s.requestSchema.isDefined then button(
            onClick(SignalsView.Msg.OpenModal(s)),
          )(span(cls := "icon is-small")(i(cls := "fas fa-paper-plane")()))
          else Empty,
        ),
      )*,
    ),
    modal.map(_.view.map(SignalsView.Msg.ForDetails(_))).getOrElse(Empty),
  )

  def update(msg: SignalsView.Msg): (SignalsView, Cmd[IO, SignalsView.Msg]) = msg match {
    case Msg.OpenModal(signal) => (this.copy(modal = Some(SignalModal(workflowInstance, signal, true))), Cmd.None)
    case Msg.ForDetails(msg)   =>
      this.modal.map(_.update(msg)) match {
        case Some((newCmp, cmd)) => this.copy(modal = Some(newCmp)) -> cmd.map(Msg.ForDetails(_))
        case None                => this                            -> Cmd.None
      }
  }

}

object SignalsView {

  enum Msg {
    case OpenModal(signal: Signal)
    case ForDetails(msg: SignalModal.Msg)
  }

}
