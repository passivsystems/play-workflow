import sbt._
import Keys._
import play.Play.autoImport._
import PlayKeys._


object ApplicationBuild extends Build {

  lazy val root = (project in file(".")).
    settings(
      organization := "com.passivsystems",
      name         := "play-workflow",
      version      := "0.0.1-SNAPSHOT",
      scalaVersion := "2.11.8",
      libraryDependencies ++= Seq(
        "com.lihaoyi"   %% "upickle"     % "0.4.1",
        "org.typelevel" %% "cats-core"   % "0.8.1",
        "org.typelevel" %% "cats-free"   % "0.8.1",
        compilerPlugin("org.spire-math" %% "kind-projector" % "0.9.3")
      ),
      scalaSource in Compile := baseDirectory.value / "src/main/scala"
    ).
    enablePlugins(play.PlayScala)
}
