/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.api.r

import java.io.{DataOutputStream, File, FileOutputStream, IOException}
import java.net.{InetAddress, InetSocketAddress, ServerSocket, Socket}
import java.util.concurrent.TimeUnit

import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.{ChannelFuture, ChannelInitializer, EventLoopGroup}
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.LengthFieldBasedFrameDecoder
import io.netty.handler.codec.bytes.{ByteArrayDecoder, ByteArrayEncoder}

import org.apache.spark.Logging

/**
 * Netty-based backend server that is used to communicate between R and Java.
 */
private[spark] class RBackend {

  private[this] var channelFuture: ChannelFuture = null
  private[this] var bootstrap: ServerBootstrap = null
  private[this] var bossGroup: EventLoopGroup = null

  def init(): Int = {
    bossGroup = new NioEventLoopGroup(2)
    val workerGroup = bossGroup
    val handler = new RBackendHandler(this)
  
    bootstrap = new ServerBootstrap()
      .group(bossGroup, workerGroup)
      .channel(classOf[NioServerSocketChannel])
  
    bootstrap.childHandler(new ChannelInitializer[SocketChannel]() {
      def initChannel(ch: SocketChannel): Unit = {
        ch.pipeline()
          .addLast("encoder", new ByteArrayEncoder())
          .addLast("frameDecoder",
            // maxFrameLength = 2G
            // lengthFieldOffset = 0
            // lengthFieldLength = 4
            // lengthAdjustment = 0
            // initialBytesToStrip = 4, i.e. strip out the length field itself
            new LengthFieldBasedFrameDecoder(Integer.MAX_VALUE, 0, 4, 0, 4))
          .addLast("decoder", new ByteArrayDecoder())
          .addLast("handler", handler)
      }
    })

    channelFuture = bootstrap.bind(new InetSocketAddress("localhost", 0))
    channelFuture.syncUninterruptibly()
    channelFuture.channel().localAddress().asInstanceOf[InetSocketAddress].getPort()
  }

  def run(): Unit = {
    channelFuture.channel.closeFuture().syncUninterruptibly()
  }

  def close(): Unit = {
    if (channelFuture != null) {
      // close is a local operation and should finish within milliseconds; timeout just to be safe
      channelFuture.channel().close().awaitUninterruptibly(10, TimeUnit.SECONDS)
      channelFuture = null
    }
    if (bootstrap != null && bootstrap.group() != null) {
      bootstrap.group().shutdownGracefully()
    }
    if (bootstrap != null && bootstrap.childGroup() != null) {
      bootstrap.childGroup().shutdownGracefully()
    }
    bootstrap = null

    // Send close to R callback server.
    if (RBackend.callbackSocket != null && 
        !RBackend.callbackSocket.isClosed()) {
      try {
        println("Requesting to close a call back server.")
        val dos = new DataOutputStream(RBackend.callbackSocket.getOutputStream())
        SerDe.writeString(dos, "close")
        RBackend.callbackSocket.close()
      }
    }
  }

}

private[spark] object RBackend extends Logging {
  
  // Channel to callback server.
  private[spark] var callbackSocket: Socket = null
    
  def main(args: Array[String]): Unit = {
    if (args.length < 1) {
      System.err.println("Usage: RBackend <tempFilePath>")
      System.exit(-1)
    }
    val sparkRBackend = new RBackend()
    try {
      // bind to random port
      val boundPort = sparkRBackend.init()
      val serverSocket = new ServerSocket(0, 1, InetAddress.getByName("localhost"))
      val listenPort = serverSocket.getLocalPort()

      // tell the R process via temporary file
      val path = args(0)
      val f = new File(path + ".tmp")
      val dos = new DataOutputStream(new FileOutputStream(f))
      dos.writeInt(boundPort)
      dos.writeInt(listenPort)
      dos.close()
      f.renameTo(new File(path))

      // wait for the end of stdin, then exit
      new Thread("wait for socket to close") {
        setDaemon(true)
        override def run(): Unit = {
          // any un-catched exception will also shutdown JVM
          val buf = new Array[Byte](1024)
          // shutdown JVM if R does not connect back in 10 seconds
          serverSocket.setSoTimeout(10000)
          try {
            val inSocket = serverSocket.accept()
            serverSocket.close()
            // wait for the end of socket, closed if R process die
            inSocket.getInputStream().read(buf)
          } finally {
            sparkRBackend.close()
            System.exit(0)
          }
        }
      }.start()

      sparkRBackend.run()
    } catch {
      case e: IOException =>
        logError("Server shutting down: failed with exception ", e)
        sparkRBackend.close()
        System.exit(1)
    }
    System.exit(0)
  }
}
