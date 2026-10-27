import Dependencies._

ThisBuild / scalaVersion := "3.8.4"
ThisBuild / version := "0.1.0-SNAPSHOT"

lazy val root = (project in file("."))
  .settings(
    name := "linux-networking"
    // libraryDependencies += munit % Test
  )
