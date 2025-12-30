// Convenience tooling for moving bundled frontend code into resources.

import java.io.File

object BundleIt {

  def bundle(from: File, to: File): Set[File] = {
    val distDir = os.Path(from)

    val viteBuild =
      os.proc("npm", "run", "build").call(distDir, stdin = os.Inherit, stdout = os.Inherit, stderr = os.Inherit)

    if (viteBuild.exitCode!= 0)  sys.error("Vite build failed")

    val sourceDir = distDir / "dist"
    val targetDir = os.Path(to) / "workflows4s-web-ui-bundle"

    os.makeDir.all(targetDir)

    os.walk(sourceDir)
      .filter(os.isFile)
      .map { file =>
        val targetPath = targetDir / file.relativeTo(sourceDir)
        os.copy.over(file, targetPath, createFolders = true)
        targetPath.toIO
      }
      .toSet
  }

}
