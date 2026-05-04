package workflows4s.testing.matrix

import cats.MonadThrow

import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

object EffectInstances {

  given scala.concurrent.ExecutionContext = ExecutionContext.global

  given MonadThrow[Future] = cats.instances.future.catsStdInstancesForFuture

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
