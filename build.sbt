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

import com.typesafe.sbt.osgi.{OsgiKeys, SbtOsgi}

/** Definition of versions. */
lazy val AkkaVersion = "2.6.14"
lazy val AkkaHttpVersion = "10.2.4"
lazy val VersionScala213 = "2.13.5"
lazy val VersionScala212 = "2.12.13"
lazy val VersionScalaXml = "1.3.0"
lazy val VersionSlf4j = "1.7.30"
lazy val VersionScalaTest = "3.2.7"
lazy val VersionWireMock = "2.27.2"
lazy val VersionMockito = "1.9.5"
lazy val VersionScalaTestMockito = "1.0.0-M2"
lazy val VersionJunit = "4.13" // needed by mockito

lazy val supportedScalaVersions = List(VersionScala213, VersionScala212)

lazy val ITest = config("integrationTest") extend Test

lazy val akkaDependencies = Seq(
  "com.typesafe.akka" %% "akka-actor-typed" % AkkaVersion,
  "com.typesafe.akka" %% "akka-stream" % AkkaVersion,
  "com.typesafe.akka" %% "akka-http" % AkkaHttpVersion,
  "com.typesafe.akka" %% "akka-http-spray-json" % AkkaHttpVersion
)

lazy val testDependencies = Seq(
  "org.scalatest" %% "scalatest" % VersionScalaTest % Test,
  "org.scalatestplus" %% "scalatestplus-mockito" % VersionScalaTestMockito % Test,
  "com.typesafe.akka" %% "akka-actor-testkit-typed" % AkkaVersion % Test,
  "com.github.tomakehurst" % "wiremock" % VersionWireMock % Test,
  "org.mockito" % "mockito-core" % VersionMockito % Test,
  "junit" % "junit" % VersionJunit % Test,
  "org.slf4j" % "slf4j-simple" % VersionSlf4j % Test
)

/** Adapt the OSGi configuration. */
lazy val projectOsgiSettings = osgiSettings ++ Seq(
  OsgiKeys.requireCapability := "osgi.ee;filter:=\"(&(osgi.ee=JavaSE)(version>=1.8))\""
)

ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature")
ThisBuild / organization := "com.github.oheger"
ThisBuild / homepage := Some(url("https://github.com/oheger/cloud-files"))
ThisBuild / scalaVersion := VersionScala213
ThisBuild / version := "0.1-SNAPSHOT"
ThisBuild / licenses := Seq(("Apache-2.0", url("https://www.apache.org/licenses/LICENSE-2.0.html")))
ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/oheger/cloud-files.git"),
    "scm:git:git@github.com:oheger/cloud-files.git"
  )
)
ThisBuild / developers := List(
  Developer(
    id = "oheger",
    name = "Oliver Heger",
    email = "oheger@apache.org",
    url = url("https://github.com/oheger")
  )
)

// Settings to publish artifacts
ThisBuild / publishMavenStyle := true
ThisBuild / pomIncludeRepository := { _ => false }
ThisBuild / publishTo := {
  val nexus = "https://oss.sonatype.org/"
  if (isSnapshot.value)
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("releases" at nexus + "service/local/staging/deploy/maven2")
}

def itFilter(name: String): Boolean = name endsWith "ITSpec"
def unitFilter(name: String): Boolean = (name endsWith "Spec") && !itFilter(name)

/** The root project which aggregates all other modules. */
lazy val CloudFiles = (project in file("."))
  .settings(
    name := "cloud-files",
    description := "A library for accessing files stored on various server types",
    crossScalaVersions := Nil,
    publish := {}
  ) aggregate(core, webDav, oneDrive, crypt, cryptAlgAES)

/**
 * The core project. This project defines the API for interacting with
 * different types of servers and provides utilities that can be used by
 * concrete protocol implementations.
 */
lazy val core = (project in file("core"))
  .enablePlugins(SbtOsgi)
  .configs(ITest)
  .settings(projectOsgiSettings)
  .settings(
    inConfig(ITest)(Defaults.testTasks),
    libraryDependencies ++= akkaDependencies,
    libraryDependencies ++= testDependencies,
    name := "cloud-files-core",
    description := "The core module of the cloud-files library",
    crossScalaVersions := supportedScalaVersions,
    OsgiKeys.exportPackage := Seq("com.github.cloudfiles.core.*"),
    OsgiKeys.privatePackage := Seq.empty,
    Test / testOptions := Seq(Tests.Filter(unitFilter)),
    ITest / testOptions := Seq(Tests.Filter(itFilter))
  )

/**
 * This project implements the CloudFiles API for WebDav servers.
 */
lazy val webDav = (project in file("webdav"))
  .enablePlugins(SbtOsgi)
  .configs(ITest)
  .settings(projectOsgiSettings)
  .settings(
    inConfig(ITest)(Defaults.testTasks),
    libraryDependencies ++= akkaDependencies,
    libraryDependencies += "org.scala-lang.modules" %% "scala-xml" % VersionScalaXml,
    libraryDependencies += "org.slf4j" % "slf4j-api" % VersionSlf4j,
    libraryDependencies ++= testDependencies,
    name := "cloud-files-webdav",
    description := "Adds support for the WebDav protocol",
    crossScalaVersions := supportedScalaVersions,
    OsgiKeys.exportPackage := Seq("com.github.cloudfiles.webdav.*"),
    OsgiKeys.privatePackage := Seq.empty,
    Test / testOptions := Seq(Tests.Filter(unitFilter)),
    ITest / testOptions := Seq(Tests.Filter(itFilter))
  ) dependsOn (core % "compile->compile;test->test")

/**
 * This project implements the CloudFiles API for Microsoft OneDrive. Refer to
 * https://docs.microsoft.com/en-us/onedrive/developer/?view=odsp-graph-online.
 */
lazy val oneDrive = (project in file("onedrive"))
  .enablePlugins(SbtOsgi)
  .configs(ITest)
  .settings(projectOsgiSettings)
  .settings(
    inConfig(ITest)(Defaults.testTasks),
    libraryDependencies ++= akkaDependencies,
    libraryDependencies += "org.slf4j" % "slf4j-api" % VersionSlf4j,
    libraryDependencies ++= testDependencies,
    name := "cloud-files-onedrive",
    description := "Adds support for Microsoft's OneDrive protocol",
    crossScalaVersions := supportedScalaVersions,
    OsgiKeys.exportPackage := Seq("com.github.cloudfiles.onedrive.*"),
    OsgiKeys.privatePackage := Seq.empty,
    Test / testOptions := Seq(Tests.Filter(unitFilter)),
    ITest / testOptions := Seq(Tests.Filter(itFilter))
  ) dependsOn (core % "compile->compile;test->test")

/**
 * This project provides extension file systems that support encrypted file
 * content and file names.
 */
lazy val crypt = (project in file("crypt"))
  .enablePlugins(SbtOsgi)
  .settings(projectOsgiSettings)
  .settings(
    libraryDependencies ++= akkaDependencies,
    libraryDependencies += "org.slf4j" % "slf4j-api" % VersionSlf4j,
    libraryDependencies ++= testDependencies,
    name := "cloud-files-crypt",
    description := "Provides encrypted file systems",
    crossScalaVersions := supportedScalaVersions,
    OsgiKeys.exportPackage := Seq("com.github.cloudfiles.crypt.*"),
    OsgiKeys.privatePackage := Seq.empty
  ) dependsOn (core % "compile->compile;test->test")

/**
 * This project contains the implementation of the AES crypto algorithm.
 */
lazy val cryptAlgAES = (project in file("crypt-algs/aes"))
  .enablePlugins(SbtOsgi)
  .settings(projectOsgiSettings)
  .settings(
    libraryDependencies ++= akkaDependencies,
    libraryDependencies ++= testDependencies,
    name := "cloud-files-cryptalg-aes",
    description := "Implements the AES crypto algorithm",
    crossScalaVersions := supportedScalaVersions,
    OsgiKeys.exportPackage := Seq("com.github.cloudfiles.crypt.alg.aes.*"),
    OsgiKeys.privatePackage := Seq.empty
  ) dependsOn (crypt, core % "test->test")
