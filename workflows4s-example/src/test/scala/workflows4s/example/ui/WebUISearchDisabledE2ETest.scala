package workflows4s.example.ui

import org.openqa.selenium.By
import org.scalatest.time.{Seconds, Span}

import scala.jdk.CollectionConverters.*

/** E2E tests verifying the UI behavior when WorkflowSearch is not provided by the server. The server is started with search disabled so the
  * `/api/v1/features` endpoint returns `searchEnabled: false`.
  */
class WebUISearchDisabledE2ETest extends WebUIE2EBase {
  override protected def serverPort: Int        = 8091
  override protected def includeSearch: Boolean = false

  "Web UI with search disabled" - {
    "should show search-unavailable notification on the Instances tab" taggedAs (E2E) in {
      navigateToInstancesTab()

      eventually(timeout(Span(15, Seconds))) {
        val notification = driver.findElement(By.cssSelector(".notification"))
        notification.isDisplayed shouldBe true
        notification.getText should include("Search functionality is not available")
      }
    }
    "should not show filter controls when search is disabled" taggedAs (E2E) in {
      navigateToInstancesTab()

      eventually(timeout(Span(15, Seconds))) {
        val notification = driver.findElement(By.cssSelector(".notification"))
        notification.isDisplayed shouldBe true
      }
      val selects = driver.findElements(By.tagName("select")).asScala
      selects shouldBe empty
    }
    "should not produce browser console errors when search is disabled" taggedAs (E2E) in {
      navigateToInstancesTab()

      // Wait for the notification to confirm the UI has rendered
      eventually(timeout(Span(15, Seconds))) {
        val notification = driver.findElement(By.cssSelector(".notification"))
        notification.isDisplayed shouldBe true
      }

      val logs         = driver.manage().logs().get("browser").getAll.asScala
      val severeErrors = logs.filter(l => l.getLevel.getName == "SEVERE" && !l.getMessage.contains("favicon.ico"))
      withClue(s"Browser console contained SEVERE errors:\n${severeErrors.map(_.getMessage).mkString("\n")}") {
        severeErrors shouldBe empty
      }
    }
    "should still display workflow list and navigation when search is disabled" taggedAs (E2E) in {
      driver.get(s"$baseUrl/ui/")

      eventually(timeout(Span(15, Seconds))) {
        val bodyText = driver.findElement(By.tagName("body")).getText
        bodyText.toUpperCase should include("AVAILABLE WORKFLOWS")

        val menuLists = driver.findElements(By.cssSelector("ul.menu-list"))
        menuLists should not be empty
      }
    }
    "should still allow navigating to Definition and Instance details tabs" taggedAs (E2E) in {
      driver.get(s"$baseUrl/ui/")

      // Click on a workflow
      eventually(timeout(Span(15, Seconds))) {
        val workflowLinks = driver.findElements(By.cssSelector("ul.menu-list li a"))
        workflowLinks should not be empty
        workflowLinks.get(0).click()
      }

      // Verify Definition tab is shown by default and has content
      eventually(timeout(Span(15, Seconds))) {
        val tabs      = driver.findElements(By.cssSelector(".tabs li")).asScala
        val activeTab = tabs.find(_.getDomAttribute("class").contains("is-active"))
        activeTab shouldBe defined
        activeTab.get.getText should include("Definition")
      }

      // Switch to Instance details tab
      val tabs              = driver.findElements(By.cssSelector("a")).asScala
      val instanceDetailTab = tabs.find(_.getText == "Instance details")
      instanceDetailTab shouldBe defined
      instanceDetailTab.get.click()

      // Verify instance ID input is present
      eventually(timeout(Span(15, Seconds))) {
        val inputs = driver.findElements(By.cssSelector("input.input")).asScala
        inputs should not be empty
      }
    }
  }
}
