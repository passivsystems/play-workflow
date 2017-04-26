import sbt._
import Keys._
import play.sbt.PlayScala

lazy val appVersion = "0.1.1-SNAPSHOT"

lazy val catsVersion = "0.9.0"

organization := "com.passivsystems"
name         := "play-workflow"
version      := appVersion
scalaVersion := "2.11.8"

libraryDependencies ++= Seq(
  "com.lihaoyi"   %% "upickle"     % "0.4.3",
  "org.typelevel" %% "cats-core"   % catsVersion,
  "org.typelevel" %% "cats-free"   % catsVersion,
  compilerPlugin("org.spire-math" %% "kind-projector" % "0.9.3"),
  // Test
  "org.scalatest"  %% "scalatest"  % "3.0.0"     % Test,
  "org.scalacheck" %% "scalacheck" % "1.13.4"    % Test,
  "org.typelevel"  %% "cats-laws"  % catsVersion % Test
)

scalaSource in Compile := baseDirectory.value / "src/main/scala"
scalaSource in Test    := baseDirectory.value / "src/test/scala"

scalacOptions ++= Seq(
  //"-Xlog-implicits",
  "-Xlint",
  "-deprecation",
  "-feature",
  "-language:higherKinds",
  "-Xfuture",
  "-Ywarn-unused-import",
  "-Ywarn-value-discard"
)

enablePlugins(PlayScala)
