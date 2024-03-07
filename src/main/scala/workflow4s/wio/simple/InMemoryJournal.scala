package workflow4s.wio.simple

import cats.effect.IO
import com.typesafe.scalalogging.StrictLogging
import workflow4s.wio.{JournalPersistance, JournalWrite}

import scala.collection.mutable

class InMemoryJournal extends JournalPersistance with StrictLogging {

  private val events: mutable.ListBuffer[Any] = mutable.ListBuffer()

  override def save[E: JournalWrite](evt: E): IO[Unit] = IO{
    logger.debug(s"Persisting event: ${evt}")
    events.append(evt)
  }

  def getEvents: List[Any] = events.toList
}
