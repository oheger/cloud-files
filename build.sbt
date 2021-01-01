/*
 * Copyright 2020-2021 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/** Definition of versions. */
lazy val AkkaVersion = "2.6.10"
lazy val AkkaHttpVersion = "10.2.2"
lazy val VersionScala = "2.13.4"
lazy val VersionScalaXml = "1.3.0"
lazy val VersionSlf4j = "1.7.30"
lazy val VersionScalaTest = "3.2.0"
lazy val VersionWireMock = "2.27.2"
lazy val VersionMockito = "1.9.5"
lazy val VersionScalaTestMockito = "1.0.0-M2"
lazy val VersionJunit = "4.13"  // needed by mockito

scalacOptions ++= Seq("-deprecation", "-feature")

lazy val ITest = config("integrationTest") extend Test

lazy val akkaDependencies = Seq(
  "com.typesafe.akka" %% "akka-actor-typed" % AkkaVersion,
  "com.typesafe.akka" %% "akka-stream" % AkkaVersion,
  "com.typesafe.akka" %% "akka-http" % AkkaHttpVersion,
  "com.typesafe.akka" %% "akka-http-spray-json" % AkkaHttpVersion,
  "com.typesafe.akka" %% "akka-actor-testkit-typed" % AkkaVersion % Test,
  "org.scala-lang" % "scala-reflect" % VersionScala
)

lazy val testDependencies = Seq(
  "org.scalatest" %% "scalatest" % VersionScalaTest % Test,
  "org.scalatestplus" %% "scalatestplus-mockito" % VersionScalaTestMockito % Test,
  "com.github.tomakehurst" % "wiremock" % VersionWireMock % Test,
  "org.mockito" % "mockito-core" % VersionMockito % Test,
  "junit" % "junit" % VersionJunit % Test,
  "org.slf4j" % "slf4j-simple" % VersionSlf4j % Test
)

ThisBuild / organization := "com.github.oheger"
ThisBuild / homepage := Some(url("https://github.com/oheger/cloud-files"))
ThisBuild / scalaVersion := VersionScala
ThisBuild / version := "0.1-SNAPSHOT"
ThisBuild / licenses := Seq(("Apache-2.0", url("https://www.apache.org/licenses/LICENSE-2.0.html")))

def itFilter(name: String): Boolean = name endsWith "ITSpec"
def unitFilter(name: String): Boolean = (name endsWith "Spec") && !itFilter(name)

lazy val CloudFiles = (project in file("."))
  .settings(
    name := "cloud-files",
    description := "A library for accessing files stored on various server types",
    scmInfo := Some(
      ScmInfo(
        url("https://github.com/oheger/cloud-files.git"),
        "scm:git:git@github.com:oheger/cloud-files.git"
      )
    ),
    developers := List(
      Developer(
        id = "oheger",
        name = "Oliver Heger",
        email = "oheger@apache.org",
        url = url("https://github.com/oheger")
      )
    )
  ) aggregate (core, webDav)

lazy val core = (project in file("core"))
  .configs(ITest)
  .settings(
    inConfig(ITest)(Defaults.testTasks),
    libraryDependencies ++= akkaDependencies,
    libraryDependencies ++= testDependencies,
    name := "cloud-files-core",
    description := "The core module of the cloud-files library",
    Test / testOptions := Seq(Tests.Filter(unitFilter)),
    ITest / testOptions := Seq(Tests.Filter(itFilter))
  )

lazy val webDav = (project in file("webdav"))
  .configs(ITest)
  .settings(
    inConfig(ITest)(Defaults.testTasks),
    libraryDependencies ++= akkaDependencies,
    libraryDependencies += "org.scala-lang.modules" %% "scala-xml" % VersionScalaXml,
    libraryDependencies += "org.slf4j" % "slf4j-api" % VersionSlf4j,
    libraryDependencies ++= testDependencies,
    name := "cloud-files-webdav",
    description := "Adds support for the WebDav protocol",
    Test / testOptions := Seq(Tests.Filter(unitFilter)),
    ITest / testOptions := Seq(Tests.Filter(itFilter))
  ) dependsOn (core % "compile->compile;test->test")
