/*
 * Copyright 2020 The Developers Team.
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
  "org.slf4j" % "slf4j-simple" % "1.7.25" % Test
)

lazy val CloudFiles = (project in file("."))
  .configs(ITest)
  .settings(inConfig(ITest)(Defaults.testSettings): _*)
  .settings(
    version := "0.1-SNAPSHOT",
    scalaVersion := VersionScala,
    libraryDependencies ++= akkaDependencies,
    libraryDependencies += "org.scala-lang.modules" %% "scala-xml" % VersionScalaXml,
    libraryDependencies ++= testDependencies,
    organization := "com.github.oheger",
    homepage := Some(url("https://github.com/oheger/cloud-files")),
    name := "cloud-files",
    description := "A library for accessing files stored on various server types",
    licenses += ("Apache-2.0", url("https://www.apache.org/licenses/LICENSE-2.0.html")),
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
    ),
    IntegrationTest / parallelExecution := false
  )
