package workflows4s.testing.matrix

import cats.MonadThrow
import workflows4s.wio.WeakSync
import zio.{Task, ZIO}

import scala.annotation.tailrec
import scala.util.Try
import scala.util.control.NonFatal

object EffectInstances {

  // WeakSync instances

  given WeakSync[Try] with {
    override def delay[A](body: => A): Try[A] = Try(body)
  }

  given weakSyncEither: WeakSync[[A] =>> Either[Throwable, A]] with {
    override def delay[A](body: => A): Either[Throwable, A] = Try(body).toEither
  }

  given WeakSync[Function0] with {
    override def delay[A](body: => A): () => A = () => body
  }

  given WeakSync[Task] with {
    override def delay[A](body: => A): Task[A] = ZIO.attempt(body)
  }

  // MonadThrow for Function0 (not provided by cats)

  given MonadThrow[Function0] with {
    override def pure[A](a: A): () => A = () => a

    override def flatMap[A, B](fa: () => A)(f: A => (() => B)): () => B = () => f(fa())()

    override def tailRecM[A, B](a: A)(f: A => (() => Either[A, B])): () => B = () => {
      @tailrec def loop(current: A): B = f(current)() match {
        case Left(next) => loop(next)
        case Right(b)   => b
      }
      loop(a)
    }

    override def raiseError[A](e: Throwable): () => A = () => throw e

    override def handleErrorWith[A](fa: () => A)(f: Throwable => (() => A)): () => A = () =>
      try fa()
      catch { case NonFatal(e) => f(e)() }
  }

}
