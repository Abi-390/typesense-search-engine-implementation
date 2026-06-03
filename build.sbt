name := """typesense-project"""
organization := "com.typesense"

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.13.18"

libraryDependencies ++= Seq(
  guice,
  ws, // Adds Play WSClient
  "io.getquill" %% "quill-jdbc" % "4.8.4", // Or quill-zio / quill-jdbc-io depending on your DB
  "mysql" % "mysql-connector-java" % "8.0.33", // Added native MySQL Driver
  "com.typesafe.play" %% "play-json" % "2.10.0"
)
