package workflows4s.runtime.wakeup.filesystem

import cats.effect.kernel.Resource
import cats.effect.{Async, IO}
import com.typesafe.scalalogging.StrictLogging
import workflows4s.runtime.wakeup.KnockerUpper
import workflows4s.runtime.wakeup.filesystem.FilesystemScheduler.EventId

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.time.format.DateTimeFormatter
import java.time.{Clock, Instant}
import java.util.HexFormat
import scala.concurrent.duration.*

class FilesystemScheduler(workdir: Path, clock: Clock, pollInterval: FiniteDuration = 1.second) extends StrictLogging {

  private val timeFormat = DateTimeFormatter.ISO_INSTANT
  private val separator  = '#'

  def schedule(time: Instant, content: String): IO[Unit] = {
    val hash     = hashString(content)
    val timeStr  = timeFormat.format(time)
    val filename = s"${timeStr}${separator}${hash}.txt"
    for {
      _ <- IO(Files.writeString(workdir.resolve(filename), content))
      _ <- IO(logger.debug(s"Scheduled wakeup ${filename}"))
    } yield ()
  }

  def events: fs2.Stream[IO, FilesystemScheduler.Event] = {
    given cats.effect.Clock[IO] = Async[IO] // compiler enters infinite loop without this
    for {
      _       <- fs2.Stream.eval(IO(logger.debug(s"Initializing scheduler polling at interval $pollInterval at ${workdir}")))
      _       <- fs2.Stream.every(pollInterval)
      file    <- fs2.io.file.Files[IO].list(fs2.io.file.Path.fromNioPath(workdir))
      timeStr  = file.fileName.toString.split(separator)(0)
      time     = timeFormat.parse(timeStr, Instant.from)
      if clock.instant().isAfter(time)
      content <- fs2.io.file.Files[IO].readUtf8(file)
    } yield FilesystemScheduler.Event(EventId(file), content)
  }

  def consume(id: EventId): IO[Unit] =
    for {
      deleted <- fs2.io.file.Files[IO].deleteIfExists(id)
      relativeId = workdir.relativize(id.toNioPath)
      _       <- if (deleted) { IO(logger.debug(s"Consumed wakeup ${relativeId}")) }
                 else { IO(logger.warn(s"No wakeup found for ${relativeId}")) }
    } yield ()

  private def hashString(input: String): String = {
    val digest    = MessageDigest.getInstance("SHA-256")
    val hashBytes = digest.digest(input.getBytes("UTF-8"))
    HexFormat.of().formatHex(hashBytes)
  }
}

object FilesystemScheduler {
  opaque type EventId <: fs2.io.file.Path = fs2.io.file.Path
  object EventId {
    def apply(path: fs2.io.file.Path): EventId = path
  }
  case class Event(id: EventId, content: String)

}

class FilesystemKnockerUpper(id: String, scheduler: FilesystemScheduler) extends KnockerUpper {
  def registerWakeup(at: Instant): IO[Unit] = scheduler.schedule(at, id)
}

object FilesystemKnockerUpper extends StrictLogging {

  def factory[Id](
      scheduler: FilesystemScheduler,
      toStr: Id => String,
      wakeup: String => IO[Unit],
  ): Resource[IO, KnockerUpper.Factory[Id]] = {
    scheduler.events
      .evalMap(event => {
        for {
          _ <- wakeup(event.content)
          _ <- IO(logger.info(s"Woken up for event ${event.id}"))
          _ <- scheduler.consume(event.id)
        } yield ()
      })
      .compile
      .drain
      .background
      .map(_ => (id: Id) => FilesystemKnockerUpper(toStr(id), scheduler))
  }

}
