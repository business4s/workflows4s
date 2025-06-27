package workflows4s.example.testuitls

import java.nio.file.{Files, Path}

import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Suite}

trait SqliteWorkdirSuite extends BeforeAndAfterAll with BeforeAndAfterEach { self: Suite =>

  private var tmpWorkdir: Option[Path] = None

  def workdir: Path = tmpWorkdir.get

  override def beforeAll(): Unit = {
    super.beforeAll()
    val dir = Files.createTempDirectory("sqlite-test-workdir-")
    tmpWorkdir = Some(dir)
  }

  override def afterAll(): Unit = {
    try {
      tmpWorkdir.foreach(deleteDirectory)
      tmpWorkdir = None
    } finally {
      super.afterAll()
    }
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    tmpWorkdir.foreach(deleteDirectory)
    val dir = Files.createTempDirectory("sqlite-test-workdir-")
    tmpWorkdir = Some(dir)
  }

  override def afterEach(): Unit = {
    try {
      tmpWorkdir.foreach(deleteDirectory)
    } finally {
      super.afterEach()
    }
  }

  private def deleteDirectory(dir: Path): Unit = {
    if Files.exists(dir) then {
      Files
        .walk(dir)
        .sorted(java.util.Comparator.reverseOrder())
        .forEach(path => Files.deleteIfExists(path): Unit)
    }
  }
}
