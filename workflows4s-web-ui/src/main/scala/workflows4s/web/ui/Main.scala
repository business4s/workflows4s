package workflows4s.web.ui

import cats.effect.IO
import tyrian.Html.*
import tyrian.*
import scala.scalajs.js.annotation.*

type Model = Int

enum Msg {
  case NoOp
}

@JSExportTopLevel("TyrianApp")
object Main extends TyrianIOApp[Msg, Model] {

  def router: Location => Msg =
    Routing.none(Msg.NoOp)

  def init(flags: Map[String, String]): (Model, Cmd[IO, Msg]) =
    (0, Cmd.None)

  def update(model: Model): Msg => (Model, Cmd[IO, Msg]) = { case Msg.NoOp =>
    (model, Cmd.None)
  }

  def view(model: Model): Html[Msg] = {
    div(
      style := """
        min-height: 100vh;
        background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
        display: flex;
        flex-direction: column;
        justify-content: center;
        align-items: center;
        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
        margin: 0;
        padding: 2rem;
      """,
    )(
      div(
        style := """
          background: rgba(255, 255, 255, 0.95);
          padding: 3rem;
          border-radius: 20px;
          box-shadow: 0 20px 40px rgba(0, 0, 0, 0.1);
          text-align: center;
          backdrop-filter: blur(10px);
          border: 1px solid rgba(255, 255, 255, 0.2);
          animation: float 3s ease-in-out infinite;
        """,
      )(
        h1(
          style := """
            font-size: 3.5rem;
            margin: 0 0 1rem 0;
            background: linear-gradient(45deg, #ff6b6b, #4ecdc4, #45b7d1);
            background-size: 200% 200%;
            -webkit-background-clip: text;
            -webkit-text-fill-color: transparent;
            animation: gradient 3s ease infinite;
            font-weight: 700;
          """,
        )("workflows4s"),
        p(
          style := """
            font-size: 1.2rem;
            color: #666;
            margin: 1rem 0 2rem 0;
            line-height: 1.6;
          """,
        )("🚀 Tyrian template is working!"),
        div(
          style := """
            display: flex;
            gap: 1rem;
            justify-content: center;
            flex-wrap: wrap;
          """,
        )(
          div(
            style := """
              background: #ff6b6b;
              color: white;
              padding: 0.8rem 1.5rem;
              border-radius: 25px;
              font-weight: 600;
              box-shadow: 0 5px 15px rgba(255, 107, 107, 0.3);
              animation: pulse 2s infinite;
            """,
          )("🎯 Workflows"),
          div(
            style := """
              background: #4ecdc4;
              color: white;
              padding: 0.8rem 1.5rem;
              border-radius: 25px;
              font-weight: 600;
              box-shadow: 0 5px 15px rgba(78, 205, 196, 0.3);
              animation: pulse 2s infinite 0.5s;
            """,
          )("⚡ Fast"),
          div(
            style := """
              background: #45b7d1;
              color: white;
              padding: 0.8rem 1.5rem;
              border-radius: 25px;
              font-weight: 600;
              box-shadow: 0 5px 15px rgba(69, 183, 209, 0.3);
              animation: pulse 2s infinite 1s;
            """,
          )("🎨 Beautiful"),
        ),
        div(
          style := """
            margin-top: 2rem;
            font-size: 0.9rem;
            color: #888;
          """,
        )("Ready for backend developers who want pretty UIs! 🎉"),
      ),
      style()("""
        @keyframes gradient {
          0% { background-position: 0% 50%; }
          50% { background-position: 100% 50%; }
          100% { background-position: 0% 50%; }
        }
        
        @keyframes float {
          0%, 100% { transform: translateY(0px); }
          50% { transform: translateY(-10px); }
        }
        
        @keyframes pulse {
          0%, 100% { transform: scale(1); }
          50% { transform: scale(1.05); }
        }
        
        body {
          margin: 0;
          padding: 0;
        }
      """),
    )
  }

  def subscriptions(model: Model): Sub[IO, Msg] =
    Sub.None
}