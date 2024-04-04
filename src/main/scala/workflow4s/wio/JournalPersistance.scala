package workflow4s.wio

import cats.effect.IO

trait JournalPersistance[Event] {

  def save(evt: Event): IO[Unit]

  def readEvents(): IO[List[Event]]

}
