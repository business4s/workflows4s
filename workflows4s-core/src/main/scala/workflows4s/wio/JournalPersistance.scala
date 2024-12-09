package workflows4s.wio

import cats.effect.IO

trait JournalPersistance[Event] extends JournalPersistance.Read[Event] with JournalPersistance.Write[Event]

object JournalPersistance {

  trait Read[+Event] {
    def readEvents(): IO[List[Event]]
  }

  trait Write[-Event] { self =>
    def save(evt: Event): IO[Unit]

    def contraMap[E1](f: E1 => Event): Write[E1] = (evt: E1) => self.save(f(evt))
  }

}
