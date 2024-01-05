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

package com.github.cloudfiles.crypt.service

import com.github.cloudfiles.crypt.alg.CryptCipher
import org.apache.pekko.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import org.apache.pekko.stream.{Attributes, FlowShape, Inlet, Outlet}
import org.apache.pekko.util.ByteString

import java.security.SecureRandom

/**
 * A flow stage to transform data using a [[CryptCipher]].
 *
 * This class supports the transformation of data via a ''CryptCipher'' while
 * it flows through a stream. The protocol of the cipher is invoked to make
 * sure that the chunks of data are transformed correctly. That way even
 * large sets of data can be transformed efficiently.
 *
 * @param cryptCipher the object to transform the data in the stream
 * @param random      the source for randomness
 */
class CryptStage(val cryptCipher: CryptCipher)(implicit random: SecureRandom)
  extends GraphStage[FlowShape[ByteString, ByteString]] {

  /**
   * Definition of a processing function. The function expects a block of data
   * and a cipher and produces a block of data to be passed downstream.
   */
  type CryptFunc = (ByteString, CryptCipher) => ByteString

  val in: Inlet[ByteString] = Inlet[ByteString]("CryptStage.in")
  val out: Outlet[ByteString] = Outlet[ByteString]("CryptStage.out")

  override val shape: FlowShape[ByteString, ByteString] = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {
      /** The current processing function. */
      private var processingFunc: CryptFunc = initProcessing

      /**
       * A flag whether data has been received by this stage. This is used to
       * determine whether a final block of data has to be handled when
       * upstream ends.
       */
      private var dataProcessed = false

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          val data = grab(in)
          val processedData = processingFunc(data, cryptCipher)
          push(out, processedData)
        }

        override def onUpstreamFinish(): Unit = {
          if (dataProcessed) {
            val finalBytes = cryptCipher.complete()
            if (finalBytes.nonEmpty) {
              push(out, finalBytes)
            }
          }
          super.onUpstreamFinish()
        }
      })

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          pull(in)
        }
      })

      /**
       * Handles initialization of data processing. This is the ''CryptFunc''
       * that is called for the first block of data received. It initializes
       * the managed cipher object, pushes the first data block downstream,
       * and sets the actual processing function.
       *
       * @param data   the first chunk of data
       * @param cipher the cipher object
       * @return the data to be pushed downstream
       */
      private def initProcessing(data: ByteString, cipher: CryptCipher): ByteString = {
        dataProcessed = true
        val result = cipher.init(random, data)
        processingFunc = cryptFunc
        result
      }
    }

  /**
   * Returns the function executing encryption / decryption logic. This
   * function is called when initialization is done to process further data
   * chunks.
   *
   * @return the function for processing data chunks
   */
  private def cryptFunc: CryptFunc = (chunk, cipher) =>
    cipher.transform(chunk)
}
