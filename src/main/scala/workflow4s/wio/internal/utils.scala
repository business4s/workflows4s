package workflow4s.wio.internal

import cats.effect.IO
import workflow4s.wio.{JournalWrite, SignalDef}

import scala.reflect.ClassTag

case class EventHandler[Evt, -In, +Out, +Err](handle: (In, Evt) => Either[Err, Out])(implicit
    val jw: JournalWrite[Evt],
    ct: ClassTag[Evt],
) {
  def expects(any: Any): Option[Evt] = ct.unapply(any)
}

case class QueryHandler[-Qr, -StIn, +Out](handle: (StIn, Qr) => Out)(implicit inCt: ClassTag[Qr], stCt: ClassTag[StIn]) {
  def run[Req, Resp](signal: SignalDef[Req, Resp])(req: Req, s: Any): Option[Resp] = {
    // thats not great but how to do better?
    for {
      qr    <- inCt.unapply(req)
      stIn  <- stCt.unapply(s)
      result = handle(stIn, qr)
      resp  <- signal.respCt.unapply(result)
    } yield resp
  }

  def ct: ClassTag[_] = inCt
}

case class SignalHandler[-Sig, +Evt, -In](handle: (In, Sig) => IO[Evt])(implicit sigCt: ClassTag[Sig]) {
  def run[Req, Resp](signal: SignalDef[Req, Resp])(req: Req, in: In): Option[IO[Evt]] = {
    sigCt.unapply(req).map(handle(in, _))
  }

  def ct: ClassTag[_] = sigCt
}