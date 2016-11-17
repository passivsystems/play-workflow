import sbt._
import Keys._
import play.Play.autoImport._
import PlayKeys._


object ApplicationBuild extends Build {

  lazy val version = "0.0.1"

  lazy val catsVersion = "0.8.1"

  lazy val root = (project in file(".")).
    settings(
      organization := "com.passivsystems",
      name         := "play-workflow",
      version      := version,
      scalaVersion := "2.11.8",
      libraryDependencies ++= Seq(
        "com.lihaoyi"   %% "upickle"     % "0.4.1",
        "org.typelevel" %% "cats-core"   % catsVersion,
        "org.typelevel" %% "cats-free"   % catsVersion,
        compilerPlugin("org.spire-math" %% "kind-projector" % "0.9.3"),
        // Test
        "org.scalatest"  %% "scalatest"  % "3.0.0"     % Test,
        "org.scalacheck" %% "scalacheck" % "1.13.4"    % Test,
        "org.typelevel"  %% "cats-laws"  % catsVersion % Test
      ),
      scalaSource in Compile := baseDirectory.value / "src/main/scala",
      scalaSource in Test    := baseDirectory.value / "src/test/scala",
      scalacOptions ++= Seq(
        //"-Xlog-implicits",
        "-deprecation",
        "-feature",
        "-language:higherKinds",
        "-Xfuture",
        "-Ywarn-unused-import",
        "-Ywarn-value-discard"
      )
    ).
    enablePlugins(play.PlayScala)
}
