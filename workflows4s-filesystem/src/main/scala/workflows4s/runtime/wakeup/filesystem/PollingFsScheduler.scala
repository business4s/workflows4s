package workflows4s.runtime.wakeup.filesystem

import cats.effect.{Async, IO}
import com.typesafe.scalalogging.StrictLogging
import fs2.io.file.{Files, Path}
import workflows4s.runtime.wakeup.filesystem.FsScheduler.TaskId

import java.time.format.DateTimeFormatter
import java.time.{Clock, Instant}
import scala.concurrent.duration.{DurationInt, FiniteDuration}

class PollingFsScheduler(workdir: Path, clock: Clock, pollInterval: FiniteDuration = 1.second) extends FsScheduler with StrictLogging {

  private val timeFormat = DateTimeFormatter.ISO_INSTANT
  private val separator  = '#'

  val files = Files[IO]

  def schedule(id: TaskId, time: Instant, content: String = ""): IO[Unit] = {
    val filename = creatFilePath(time, id)
    for {
      _ <- fs2.Stream.emit(content).through(files.writeUtf8(workdir.resolve(filename))).compile.drain
      _ <- IO(logger.debug(s"Scheduled wakeup ${filename}"))
    } yield ()
  }

  def events: fs2.Stream[IO, FsScheduler.Event] = {
    given cats.effect.Clock[IO] = Async[IO] // compiler enters infinite loop without this
    for {
      _             <- fs2.Stream.eval(IO(logger.debug(s"Initializing scheduler polling at interval $pollInterval at ${workdir}")))
      _             <- fs2.Stream.every(pollInterval)
      file          <- files.list(workdir)
      (time, taskId) = parseFileName(file)
      if clock.instant().isAfter(time)
      content       <- fs2.Stream.eval(IO(java.nio.file.Files.readString(file.toNioPath)))
    } yield FsScheduler.Event(taskId, time, content)
  }

  def clearAll(id: TaskId): IO[Unit] = files
    .list(workdir)
    .filter(file => {
      val (_, trigerTaskId) = parseFileName(file)
      trigerTaskId == id
    })
    .evalMap(file => files.deleteIfExists(file))
    .compile
    .drain

  def clear(id: TaskId, time: Instant): IO[Unit] = {
    val file = creatFilePath(time, id)
    for {
      deleted <- files.deleteIfExists(file)
      _       <- if (deleted) { IO(logger.debug(s"Consumed wakeup ${file.fileName}")) }
                 else { IO(logger.warn(s"No wakeup found for ${file.fileName}")) }
    } yield ()
  }

  private def creatFilePath(time: Instant, id: TaskId): Path = {
    val timeStr  = timeFormat.format(time)
    val fileName = s"${timeStr}${separator}${id}.txt"
    workdir.resolve(fileName)
  }
  private def parseFileName(file: Path): (Instant, TaskId)   = {
    val fileName      = file.fileName.toString
    val timeStr       = fileName.split(separator)(0)
    val time: Instant = timeFormat.parse(timeStr, Instant.from)
    val idStr         = fileName.stripPrefix(timeStr).stripPrefix(separator.toString).stripSuffix(".txt")
    (time, TaskId(idStr))
  }
}
