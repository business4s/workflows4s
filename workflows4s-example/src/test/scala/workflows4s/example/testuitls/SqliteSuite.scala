package workflows4s.example.testuitls

import java.nio.file.{Files, Path}

import org.scalatest.{BeforeAndAfterAll, Suite}

trait SqliteSuite extends BeforeAndAfterAll { self: Suite =>

  private var tmpDbFile: Option[Path] = None

  def dbFilePath: Path = tmpDbFile.get

  override def beforeAll(): Unit = {
    super.beforeAll()

    val file = Files.createTempFile(s"sqlite-test-", ".db")

    file.toFile.deleteOnExit()
    tmpDbFile = Some(file)

  }

  override def afterAll(): Unit = {
    try {
      tmpDbFile.foreach(f => f.toFile.delete())
      tmpDbFile = None
    } finally {
      super.afterAll()
    }
  }

}
