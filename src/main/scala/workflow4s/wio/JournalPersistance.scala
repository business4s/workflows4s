package workflow4s.wio

import cats.effect.IO

trait JournalPersistance {

  def save[E: JournalWrite](evt: E): IO[Unit]

  def readEvents(): IO[List[Any]]

}
