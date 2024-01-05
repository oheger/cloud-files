/*
 * Copyright 2020-2024 The Developers Team.
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

/** The version of this project. */
lazy val CloudFilesVersion = "0.8-SNAPSHOT"

/** Supported Scala versions. */
lazy val VersionScala213 = "2.13.12"
lazy val VersionScala212 = "2.12.18"
lazy val VersionScala3 = "3.3.1"

/** Versions of compile-time dependencies. */
lazy val VersionPekko = "1.0.2"
lazy val VersionPekkoHttp = "1.0.0"
lazy val VersionSlf4j = "2.0.10"

/** Versions of test dependencies. */
lazy val VersionScalaTest = "3.2.17"
lazy val VersionScalaTestMockito = "3.2.17.0"
lazy val VersionScalaXml = "2.2.0"
lazy val VersionWireMock = "3.3.1"

lazy val supportedScalaVersions = List(VersionScala213, VersionScala212, VersionScala3)

lazy val ITest = config("integrationTest") extend Test

lazy val akkaDependencies = Seq(
  "org.apache.pekko" %% "pekko-actor-typed" % VersionPekko,
  "org.apache.pekko" %% "pekko-stream" % VersionPekko,
  "org.apache.pekko" %% "pekko-http" % VersionPekkoHttp,
  "org.apache.pekko" %% "pekko-http-spray-json" % VersionPekkoHttp
)

lazy val testDependencies = Seq(
  "org.scalatest" %% "scalatest" % VersionScalaTest % Test,
  "org.scalatestplus" %% "mockito-4-11" % VersionScalaTestMockito % Test,
  "org.apache.pekko" %% "pekko-actor-testkit-typed" % VersionPekko % Test,
  "org.wiremock" % "wiremock" % VersionWireMock % Test,
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
ThisBuild / version := CloudFilesVersion
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
  ) aggregate(core, webDav, oneDrive, googleDrive, localFs, crypt, cryptAlgAES)

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
    libraryDependencies += "org.slf4j" % "slf4j-api" % VersionSlf4j,
    libraryDependencies ++= testDependencies,
    libraryDependencies += "org.scala-lang.modules" %% "scala-xml" % VersionScalaXml % Test,
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
 * This project implements the CloudFiles API for Google Drive. Refer to
 * https://developers.google.com/drive/api/v3/about-files.
 */
lazy val googleDrive = (project in file("gdrive"))
  .enablePlugins(SbtOsgi)
  .configs(ITest)
  .settings(projectOsgiSettings)
  .settings(
    inConfig(ITest)(Defaults.testTasks),
    libraryDependencies ++= akkaDependencies,
    libraryDependencies += "org.slf4j" % "slf4j-api" % VersionSlf4j,
    libraryDependencies ++= testDependencies,
    name := "cloud-files-googledrive",
    description := "Adds support for the GoogleDrive protocol",
    crossScalaVersions := supportedScalaVersions,
    OsgiKeys.exportPackage := Seq("com.github.cloudfiles.gdrive.*"),
    OsgiKeys.privatePackage := Seq.empty,
    Test / testOptions := Seq(Tests.Filter(unitFilter)),
    ITest / testOptions := Seq(Tests.Filter(itFilter))
  ) dependsOn (core % "compile->compile;test->test")

/**
 * This project provides a FileSystem implementation based on the local file
 * system, e.g. the machine's hard drive.
 */
lazy val localFs = (project in file("localfs"))
  .enablePlugins(SbtOsgi)
  .settings(projectOsgiSettings)
  .settings(
    libraryDependencies ++= akkaDependencies,
    libraryDependencies += "org.slf4j" % "slf4j-api" % VersionSlf4j,
    libraryDependencies ++= testDependencies,
    name := "cloud-files-localfs",
    description := "A file system implementation for the local file system",
    crossScalaVersions := supportedScalaVersions,
    OsgiKeys.exportPackage := Seq("com.github.cloudfiles.localfs.*"),
    OsgiKeys.privatePackage := Seq.empty
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
