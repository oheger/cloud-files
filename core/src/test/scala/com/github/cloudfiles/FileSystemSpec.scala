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

package com.github.cloudfiles

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.HttpEntity
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.github.cloudfiles.FileSystem.Operation
import com.github.cloudfiles.http.HttpRequestSender
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.Future

object FileSystemSpec {

  /**
   * A data class representing a file in the test file system.
   *
   * @param name the file name
   */
  case class TestFileImpl(name: String)

  /**
   * A data class representing a folder in the test file system.
   *
   * @param name the folder name
   */
  case class TestFolderImpl(name: String)

  /**
   * A dummy implementation of a file system that can serve as base class for
   * special implementations in test cases. All functions are implemented
   * throwing an exception.
   */
  class FileSystemImpl extends FileSystem[String, TestFileImpl, TestFolderImpl, String] {
    override def resolvePath(path: String)(implicit system: ActorSystem[_]): Operation[String] =
      throw new UnsupportedOperationException("Unexpected invocation")

    override def rootID(implicit system: ActorSystem[_]): Operation[String] =
      throw new UnsupportedOperationException("Unexpected invocation")

    override def resolveFile(id: String)(implicit system: ActorSystem[_]): Operation[TestFileImpl] =
      throw new UnsupportedOperationException("Unexpected invocation")

    override def resolveFolder(id: String)(implicit system: ActorSystem[_]): Operation[TestFolderImpl] =
      throw new UnsupportedOperationException("Unexpected invocation")

    override def folderContent(id: String)(implicit system: ActorSystem[_]): Operation[String] =
      throw new UnsupportedOperationException("Unexpected invocation")

    override def createFolder(parent: String, folder: Model.Folder[String])(implicit system: ActorSystem[_]):
    Operation[String] =
      throw new UnsupportedOperationException("Unexpected invocation")

    override def updateFolder(folder: Model.Folder[String])(implicit system: ActorSystem[_]):
    Operation[Unit] =
      throw new UnsupportedOperationException("Unexpected invocation")

    override def deleteFolder(folderID: String)(implicit system: ActorSystem[_]): Operation[Unit] =
      throw new UnsupportedOperationException("Unexpected invocation")

    override def createFile(parent: String, file: Model.File[String], content: Source[ByteString, Any])
                           (implicit system: ActorSystem[_]): Operation[String] =
      throw new UnsupportedOperationException("Unexpected invocation")

    override def updateFile(file: Model.File[String])(implicit system: ActorSystem[_]): Operation[Unit] =
      throw new UnsupportedOperationException("Unexpected invocation")

    override def updateFileContent(fileID: String, size: Int, content: Source[ByteString, Any])
                                  (implicit system: ActorSystem[_]): Operation[Unit] =
      throw new UnsupportedOperationException("Unexpected invocation")

    override def downloadFile(fileID: String)(implicit system: ActorSystem[_]): Operation[HttpEntity] =
      throw new UnsupportedOperationException("Unexpected invocation")

    override def deleteFile(fileID: String)(implicit system: ActorSystem[_]): Operation[Unit] =
      throw new UnsupportedOperationException("Unexpected invocation")
  }

  /**
   * Returns an operation that checks the passed in actor against an expected
   * actor reference.
   *
   * @param expActor the expected actor reference
   * @param f        the function to obtain the operation result
   * @tparam A the result type of the operation
   * @return the operation
   */
  private def checkActorOp[A](expActor: ActorRef[HttpRequestSender.HttpCommand])
                             (f: ActorRef[HttpRequestSender.HttpCommand] => Future[A]): Operation[A] =
    Operation(actor =>
      if (actor == expActor) f(actor)
      else Future.failed(new IllegalArgumentException("Unexpected actor: " + actor))
    )
}

/**
 * Test class of ''FileSystem''. This class tests the composed operations that
 * are already implemented by the trait. This includes the monadic features of
 * the ''Operation'' result class.
 */
class FileSystemSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers with MockitoSugar
  with AsyncTestHelper {

  import FileSystemSpec._

  "FileSystem" should "resolve a folder by its path" in {
    val FolderPath = "my/test/folder"
    val FolderID = "theFolder"
    val folder = TestFolderImpl("some folder")
    val requestActor = mock[ActorRef[HttpRequestSender.HttpCommand]]
    val fs = new FileSystemImpl {
      override def resolvePath(path: String)(implicit system: ActorSystem[_]): Operation[String] =
        if (path == FolderPath)
          checkActorOp(requestActor)(_ => Future.successful(FolderID))
        else super.resolvePath(path)

      override def resolveFolder(id: String)(implicit system: ActorSystem[_]): Operation[TestFolderImpl] =
        if (id == FolderID) {
          checkActorOp(requestActor)(_ => Future.successful(folder))
        } else super.resolveFolder(id)
    }

    val op = fs.resolveFolderByPath(FolderPath)
    val result = futureResult(op.run(requestActor))
    result should be(folder)
  }

  it should "handle a failed future in a combined operation" in {
    val exception = new IllegalStateException("Cannot resolve")
    val requestActor = mock[ActorRef[HttpRequestSender.HttpCommand]]
    val fs = new FileSystemImpl {
      override def resolvePath(path: String)(implicit system: ActorSystem[_]): Operation[String] =
        Operation(_ => Future.failed(exception))
    }

    val op = fs.resolveFolderByPath("some/folder/path")
    expectFailedFuture[IllegalStateException](op.run(requestActor)) should be(exception)
  }

  it should "resolve a file by its path" in {
    val FilePath = "test/data/test.jpg"
    val FileID = "testFileID"
    val file = TestFileImpl("testFile.txt")
    val requestActor = mock[ActorRef[HttpRequestSender.HttpCommand]]
    val fs = new FileSystemImpl {
      override def resolvePath(path: String)(implicit system: ActorSystem[_]): Operation[String] =
        if (path == FilePath)
          checkActorOp(requestActor)(_ => Future.successful(FileID))
        else super.resolvePath(path)

      override def resolveFile(id: String)(implicit system: ActorSystem[_]): Operation[TestFileImpl] = {
        if (id == FileID)
          checkActorOp(requestActor)(_ => Future.successful(file))
        else super.resolveFile(id)
      }
    }

    val op = fs.resolveFileByPath(FilePath)
    val result = futureResult(op.run(requestActor))
    result should be(file)
  }

  it should "obtain the content of the root folder" in {
    val RootID = "*theRoot*"
    val RootContent = "{representation of the root content}"
    val requestActor = mock[ActorRef[HttpRequestSender.HttpCommand]]
    val fs = new FileSystemImpl {
      override def rootID(implicit system: ActorSystem[_]): Operation[String] =
        checkActorOp(requestActor)(_ => Future.successful(RootID))

      override def folderContent(id: String)(implicit system: ActorSystem[_]): Operation[String] = {
        if (id == RootID)
          checkActorOp(requestActor)(_ => Future.successful(RootContent))
        else super.folderContent(id)
      }
    }

    val op = fs.rootFolderContent
    val result = futureResult(op.run(requestActor))
    result should be(RootContent)
  }
}
