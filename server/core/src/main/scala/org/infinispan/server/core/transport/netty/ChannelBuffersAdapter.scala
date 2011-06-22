package org.infinispan.server.core.transport.netty

import org.infinispan.server.core.transport.{ChannelBuffer}
import org.jboss.netty.buffer.{ChannelBuffers => NettyChannelBuffers}

/**
 * A channel buffers factory adapter for Netty buffers.
 *
 * @author Galder Zamarreño
 * @since 4.1
 */
object ChannelBuffersAdapter {

   def wrappedBuffer(array: Array[Byte]*): ChannelBuffer = {
      new ChannelBufferAdapter(NettyChannelBuffers.wrappedBuffer(array : _*));
   }
   
   def dynamicBuffer(): ChannelBuffer = {
      new ChannelBufferAdapter(NettyChannelBuffers.dynamicBuffer());
   }

}