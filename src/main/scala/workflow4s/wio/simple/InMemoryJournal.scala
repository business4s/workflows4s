package workflow4s.wio.simple

import cats.effect.IO
import workflow4s.wio.{JournalPersistance, JournalWrite}

import scala.collection.mutable

class InMemoryJournal extends JournalPersistance {

  private val events: mutable.ListBuffer[Any] = mutable.ListBuffer()

  override def save[E: JournalWrite](evt: E): IO[Unit] = IO(events.append(evt))

  def getEvents: List[Any] = events.toList
}
