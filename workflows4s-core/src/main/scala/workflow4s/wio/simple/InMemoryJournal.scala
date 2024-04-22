package workflow4s.wio.simple

import cats.effect.IO
import com.typesafe.scalalogging.StrictLogging
import workflow4s.wio.JournalPersistance

import scala.collection.mutable

class InMemoryJournal[E] extends JournalPersistance[E] with StrictLogging {

  private val events: mutable.ListBuffer[E] = mutable.ListBuffer()

  override def save(evt: E): IO[Unit] = IO {
    logger.debug(s"Persisting event: ${evt}")
    events.append(evt)
  }

  def readEvents(): IO[List[E]] = IO(events.toList)
}
