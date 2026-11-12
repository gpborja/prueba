name := "obp-billing"
organization := "com.tesobe"
version := "0.2.0"

scalaVersion := "2.13.18"

libraryDependencies += "net.liftweb" %% "lift-json" % "3.5.0"

scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Xlint",
  "-Wunused:imports"
)

// The JDK's java.net.http client is used for HTTP, so Java 11+ is required.
javacOptions ++= Seq("--release", "11")

val mainClassName = "com.tesobe.obp.billing.Main"
Compile / run / mainClass := Some(mainClassName)
Compile / packageBin / mainClass := Some(mainClassName)

assembly / mainClass := Some(mainClassName)
assembly / assemblyJarName := "obp-billing.jar"
assembly / assemblyMergeStrategy := {
  case PathList("META-INF", _ @ _*) => MergeStrategy.discard
  case _                            => MergeStrategy.first
}

libraryDependencies += "org.scalameta" %% "munit" % "1.3.5" % Test
