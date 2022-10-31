package zio.sbt

import mdoc.MdocPlugin
import mdoc.MdocPlugin.autoImport.*
import sbt.*
import sbt.Keys.*

import java.nio.file.Paths
import scala.language.postfixOps

object WebsitePlugin extends sbt.AutoPlugin {

  object autoImport {
    val compileDocs            = inputKey[Unit]("compile docs")
    val installWebsite         = taskKey[Unit]("install the website for the first time")
    val previewWebsite         = taskKey[Unit]("preview website")
    val publishToNpm           = inputKey[Unit]("publish website to the npm registry")
    val generateGithubWorkflow = taskKey[Unit]("generate github workflow")
    val npmToken               = settingKey[String]("npm token")
    val docsDependencies       = settingKey[Seq[ModuleID]]("documentation project dependencies")
  }

  import autoImport.*

  override def requires = MdocPlugin

  override lazy val projectSettings =
    Seq(
      compileDocs := compileDocsTask.evaluated,
      mdocOut := Paths.get("target/website/docs").toFile,
      installWebsite := installWebsiteTask.value,
      previewWebsite := previewWebsiteTask.value,
      publishToNpm := publishWebsiteTask.value,
      generateGithubWorkflow := generateGithubWorkflowTask.value,
      docsDependencies := Seq.empty,
      libraryDependencies ++= docsDependencies.value,
      mdocVariables := {
        import sys.process.*

        val releaseVersion =
          ("git tag --sort=committerdate" !!).split("\n").filter(_.startsWith("v")).last.tail

        mdocVariables.value ++
          Map(
            "VERSION"          -> version.value,
            "RELEASE_VERSION"  -> releaseVersion
          )
      }
    )

  private def exit(exitCode: Int) = if (exitCode != 0) sys.exit(exitCode)

  lazy val previewWebsiteTask = Def.task {
    import zio.*

    val task =
      for {
        _ <- ZIO.attempt(compileDocsTask.toTask(" --watch").value).forkDaemon
        _ <- ZIO.attempt(docusaurusServerTask.value)
      } yield ()

    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(task).getOrThrowFiberFailure()
    }
  }
    .dependsOn(compileDocsTask.toTask(""))

  lazy val docusaurusServerTask = Def.task {
    import sys.process.*
    exit("yarn --cwd ./target/website run start" !)
  }

  lazy val compileDocsTask =
    Def.inputTaskDyn {
      val parsed =
        sbt.complete.DefaultParsers.spaceDelimited("<arg>").parsed
      val watch  =
        parsed.headOption.getOrElse("").equalsIgnoreCase("--watch")
      val logger = streams.value.log
      logger.info("Compiling docs using mdoc ...")

      if (watch)
        mdoc.toTask(" --watch --no-livereload")
      else
        mdoc.toTask("")
    }

  lazy val installWebsiteTask =
    Def.task {
      import sys.process.*
      val logger = streams.value.log

      val task: String =
        s"""|npx @zio.dev/create-zio-website@latest ${normalizedName.value}-website \\
            |  --description="${name.value}" \\
            |  --author="ZIO Contributors" \\
            |  --email="email@zio.dev" \\
            |  --license="Apache-2.0" \\
            |  --architecture=Linux""".stripMargin

      logger.info(s"installing website for ${normalizedName.value} ... \n$task")

      exit(Process(task, new File("target/")) !)

      exit(Process(s"mv ${normalizedName.value}-website website", new File("target/")) !)

      exit("rm target/website/.git/ -rvf" !)
    }

  lazy val publishWebsiteTask =
    Def.task {
      import sys.process.*

      val version =
        ("git tag --sort=committerdate" !!).split("\n").last
        .replace("docs-", "")

      exit(Process(s"npm version $version", new File("target/website/docs/")) !)

      exit("npm config set access public" !)

      exit(Process("npm publish", new File("target/website/docs/")).!)

    }

  lazy val generateGithubWorkflowTask =
    Def.task {
      val template =
        """|# This file was autogenerated using `zio-sbt` via `sbt generateGithubWorkflow` 
           |# task and should be included in the git repository. Please do not edit 
           |# it manually. 
           |
           |name: Documentation
           |
           |on:
           |  release:
           |    types: [created]
           |  workflow_dispatch:
           |    branches: [ main ]
           |
           |jobs:
           |  publish-docs:
           |    runs-on: ubuntu-20.04
           |    timeout-minutes: 30
           |    steps:
           |      - uses: actions/checkout@v3.1.0
           |        with:
           |          fetch-depth: 0
           |      - name: Print Latest Tag For Debugging Purposes
           |        run: git tag --sort=committerdate | tail -1
           |      - uses: olafurpg/setup-scala@v13
           |      - name: Compile zio-sbt
           |        run: |
           |          git clone https://github.com/khajavi/zio-sbt.git
           |          cd zio-sbt
           |          sbt zioSbtWebsite/publishLocal
           |      - name: Compile Project's Documentation
           |        run: sbt compileDocs
           |      - uses: actions/setup-node@v3
           |        with:
           |          node-version: '16.x'
           |          registry-url: 'https://registry.npmjs.org'
           |      - name: Publishing Docs to NPM Registry
           |        run: sbt publishToNpm
           |        env:
           |          NODE_AUTH_TOKEN: ${{ secrets.NPM_TOKEN }}
           |""".stripMargin

      IO.write(new File(".github/workflows/documentation.yml"), template)
    }

}
