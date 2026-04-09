package workflows4s.wio

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/** Lifts a lazy synchronous computation into an effect type `F[_]`.
  *
  * This is the integration point between the engine's sync operations (logging, clock reads) and the user's chosen effect system. A minimal
  * alternative to `cats.effect.Sync` — only provides `delay`.
  */
trait WeakSync[F[_]] {
  def delay[A](body: => A): F[A]
}

object WeakSync {
  def apply[F[_]](using ev: WeakSync[F]): WeakSync[F] = ev

  given WeakSync[Try] with {
    override def delay[A](body: => A): Try[A] = Try(body)
  }

  given WeakSync[[A] =>> Either[Throwable, A]] with {
    override def delay[A](body: => A): Either[Throwable, A] = Try(body).toEither
  }

  given WeakSync[Function0] with {
    override def delay[A](body: => A): () => A = () => body
  }

  given (using ec: ExecutionContext): WeakSync[Future] with {
    override def delay[A](body: => A): Future[A] = Future(body)
  }
}
