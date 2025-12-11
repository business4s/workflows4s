package workflows4s.wio

trait JournalPersistance[F[_], Event] extends JournalPersistance.Read[F, Event] with JournalPersistance.Write[F, Event]

object JournalPersistance {

  trait Read[F[_], Event] {
    def readEvents(): F[List[Event]]
  }

  trait Write[F[_], Event] { self =>
    def save(evt: Event): F[Unit]

    def contraMap[E1](f: E1 => Event): Write[F, E1] = (evt: E1) => self.save(f(evt))
  }

}
