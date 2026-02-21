package workflows4s.example.ui

import org.openqa.selenium.By
import org.scalatest.time.{Seconds, Span}

import scala.jdk.CollectionConverters.*

class WebUIE2ETest extends WebUIE2EBase {
  override protected def serverPort: Int = 8090

  private val workflowNames = List("Course Registration", "Pull Request", "Withdrawal")

  "Web UI E2E Tests" - {
    "should load the UI home page" taggedAs (E2E) in {
      driver.get(s"$baseUrl/ui/")
      eventually(timeout(Span(10, Seconds))) {
        driver.getTitle should not be empty
      }
      driver.getCurrentUrl should startWith(s"$baseUrl/ui/")
    }
    "should redirect from root to /ui/" taggedAs (E2E) in {
      driver.get(baseUrl)
      eventually(timeout(Span(10, Seconds))) {
        driver.getCurrentUrl should startWith(s"$baseUrl/ui/")
      }
    }
    "should redirect from /ui to /ui/" taggedAs (E2E) in {
      driver.get(s"$baseUrl/ui")
      eventually(timeout(Span(10, Seconds))) {
        driver.getCurrentUrl should startWith(s"$baseUrl/ui/")
      }
    }
    "should display workflow list" taggedAs (E2E) in {
      driver.get(s"$baseUrl/ui/")
      eventually(timeout(Span(15, Seconds))) {
        val bodyText = driver.findElement(By.tagName("body")).getText
        bodyText.toUpperCase should include("AVAILABLE WORKFLOWS")

        workflowNames.foreach(name => bodyText should include(name))

        val menuLists = driver.findElements(By.cssSelector("ul.menu-list"))
        menuLists should not be empty
      }
    }
    "should display filter controls when search is enabled" taggedAs (E2E) in {
      navigateToInstancesTab()

      eventually(timeout(Span(15, Seconds))) {
        val selects = driver.findElements(By.tagName("select")).asScala
        selects should not be empty
        selects.foreach { select =>
          select.isDisplayed shouldBe true
          select.isEnabled shouldBe true
        }
      }
    }
    "should not show search-unavailable notification when search is enabled" taggedAs (E2E) in {
      navigateToInstancesTab()

      eventually(timeout(Span(15, Seconds))) {
        // Wait for the Instances tab content to render
        val selects = driver.findElements(By.tagName("select")).asScala
        selects should not be empty
      }
      // The "not available" notification should not be present
      val notifications = driver.findElements(By.cssSelector(".notification")).asScala
      notifications.filter(_.getText.contains("Search functionality is not available")) shouldBe empty
    }
    "should handle API calls gracefully" taggedAs (E2E) in {
      driver.get(s"$baseUrl/ui/")
      eventually(timeout(Span(15, Seconds))) {
        val logs         = driver.manage().logs().get("browser").getAll.asScala
        val severeErrors = logs.filter(l => l.getLevel.getName == "SEVERE" && !l.getMessage.contains("favicon.ico"))
        withClue(s"Browser console contained SEVERE errors:\n${severeErrors.map(_.getMessage).mkString("\n")}") {
          severeErrors shouldBe empty
        }
        driver.findElement(By.tagName("body")).isDisplayed shouldBe true
      }
    }
  }
}
