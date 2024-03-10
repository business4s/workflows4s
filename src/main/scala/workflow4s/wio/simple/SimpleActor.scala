package workflow4s.wio.simple

import cats.effect.unsafe.IORuntime
import cats.implicits.catsSyntaxEitherId
import com.typesafe.scalalogging.StrictLogging
import workflow4s.wio.Interpreter.{EventResponse, ProceedResponse, QueryResponse, SignalResponse}
import workflow4s.wio.simple.SimpleActor.EventResponse
import workflow4s.wio.{ActiveWorkflow, Interpreter, JournalPersistance, SignalDef, WIO}

class SimpleActor(private var wf: ActiveWorkflow, journal: JournalPersistance)(implicit IORuntime: IORuntime) extends StrictLogging {

  def handleSignal[Req, Resp](signalDef: SignalDef[Req, Resp])(req: Req): SimpleActor.SignalResponse[Resp] = {
    logger.debug(s"Handling signal ${req}")
    wf.handleSignal(signalDef)(req) match {
      case SignalResponse.Ok(value)          =>
        val (newWf, resp) = value.unsafeRunSync()
        wf = newWf
        logger.debug(s"Signal handled. Next state: ${newWf.getDesc}")
        proceed(runIO = true)
        SimpleActor.SignalResponse.Ok(resp)
      case SignalResponse.UnexpectedSignal() =>
        logger.debug(s"Unexpected signal ${req}. Wf: ${wf.getDesc}")
        SimpleActor.SignalResponse.UnexpectedSignal(wf.getDesc)
    }
  }

  def handleQuery[Req, Resp](signalDef: SignalDef[Req, Resp])(req: Req): SimpleActor.QueryResponse[Resp] =
    wf.handleQuery(signalDef)(req) match {
      case QueryResponse.Ok(value)         => SimpleActor.QueryResponse.Ok(value)
      case QueryResponse.UnexpectedQuery() => SimpleActor.QueryResponse.UnexpectedQuery(wf.getDesc)
    }

  protected def handleEvent(event: Any): SimpleActor.EventResponse = {
    logger.debug(s"Handling event: ${event}")
    val resp = wf.handleEvent(event) match {
      case Interpreter.EventResponse.Ok(newFlow)       =>
        wf = newFlow
        proceed(false)
        SimpleActor.EventResponse.Ok
      case Interpreter.EventResponse.UnexpectedEvent() => SimpleActor.EventResponse.UnexpectedEvent(wf.getDesc)
    }
    logger.debug(s"Event response: ${resp}. New wf: ${wf.getDesc}")
    resp
  }

  def proceed(runIO: Boolean): Unit = {
    logger.debug(s"Proceeding to the next step. Run io: ${runIO}")
    wf.proceed(runIO) match {
      case ProceedResponse.Executed(newFlowIO) =>
        wf = newFlowIO.unsafeRunSync()
        logger.debug(s"Proceeded. New wf: ${wf.getDesc}")
        proceed(runIO)
      case ProceedResponse.Noop()              =>
        logger.debug(s"Can't proceed. Wf: ${wf.getDesc}")
        ()
    }
  }

  def recover(): Unit = {
    journal
      .readEvents()
      .unsafeRunSync()
      .foreach(e =>
        this.handleEvent(e) match {
          case SimpleActor.EventResponse.Ok                    => ()
          case SimpleActor.EventResponse.UnexpectedEvent(desc) => throw new IllegalArgumentException(s"Unexpected event :${desc}")
        },
      )
    this.proceed(runIO = true)
  }

}

object SimpleActor {

  def create[SIn](behaviour: WIO[Nothing, Unit, SIn, Any], state: SIn, journalPersistance: JournalPersistance)(implicit ior: IORuntime): SimpleActor =
    new SimpleActor(ActiveWorkflow(behaviour, new Interpreter(journalPersistance), (state, ()).asRight), journalPersistance)

  sealed trait SignalResponse[+Resp]
  object SignalResponse {
    case class Ok[Resp](result: Resp)        extends SignalResponse[Resp]
    case class UnexpectedSignal(msg: String) extends SignalResponse[Nothing]
  }

  sealed trait EventResponse
  object EventResponse {
    case object Ok                          extends EventResponse
    case class UnexpectedEvent(msg: String) extends EventResponse
  }

  sealed trait QueryResponse[+Resp]
  object QueryResponse {
    case class Ok[Resp](result: Resp)       extends QueryResponse[Resp]
    case class UnexpectedQuery(msg: String) extends QueryResponse[Nothing]
  }
}
