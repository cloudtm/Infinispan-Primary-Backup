package org.infinispan.server.hotrod

import OperationStatus._
import OperationResponse._
import org.infinispan.util.Util

/**
 * A basic responses. The rest of this file contains other response types.
 *
 * @author Galder Zamarreño
 * @since 4.1
 */
class Response(val messageId: Long, val cacheName: String, val clientIntel: Short, val operation: OperationResponse,
               val status: OperationStatus, val topologyId: Int) {
   override def toString = {
      new StringBuilder().append("Response").append("{")
         .append("messageId=").append(messageId)
         .append(", operation=").append(operation)
         .append(", status=").append(status)
         .append(", cacheName=").append(cacheName)
         .append("}").toString
   }
}

class ResponseWithPrevious(override val messageId: Long, override val cacheName: String,
                           override val clientIntel: Short, override val operation: OperationResponse,
                           override val status: OperationStatus,
                           override val topologyId: Int,
                           val previous: Option[Array[Byte]])
      extends Response(messageId, cacheName, clientIntel, operation, status, topologyId) {
   override def toString = {
      new StringBuilder().append("ResponseWithPrevious").append("{")
         .append("messageId=").append(messageId)
         .append(", operation=").append(operation)
         .append(", status=").append(status)
         .append(", previous=").append(if (previous == None) "null" else Util.printArray(previous.get, true))
         .append("}").toString
   }
}

class GetResponse(override val messageId: Long, override val cacheName: String, override val clientIntel: Short,
                  override val operation: OperationResponse, override val status: OperationStatus,
                  override val topologyId: Int,
                  val data: Option[Array[Byte]])
      extends Response(messageId, cacheName, clientIntel, operation, status, topologyId) {
   override def toString = {
      new StringBuilder().append("GetResponse").append("{")
         .append("messageId=").append(messageId)
         .append(", operation=").append(operation)
         .append(", status=").append(status)
         .append(", data=").append(if (data == None) "null" else Util.printArray(data.get, true))
         .append("}").toString
   }
}
class BulkGetResponse(override val messageId: Long, override val cacheName: String, override val clientIntel: Short,
                  override val operation: OperationResponse, override val status: OperationStatus,
                  override val topologyId: Int, val count: Int)
      extends Response(messageId, cacheName, clientIntel, operation, status, topologyId) {
   override def toString = {
      new StringBuilder().append("BulkGetResponse").append("{")
         .append("messageId=").append(messageId)
         .append(", operation=").append(operation)
         .append(", status=").append(status)
         .append(", data=").append("}").toString
   }
}

class GetWithVersionResponse(override val messageId: Long, override val cacheName: String,
                             override val clientIntel: Short, override val operation: OperationResponse,
                             override val status: OperationStatus,
                             override val topologyId: Int,
                             override val data: Option[Array[Byte]], val version: Long)
      extends GetResponse(messageId, cacheName, clientIntel, operation, status, topologyId, data) {
   override def toString = {
      new StringBuilder().append("GetWithVersionResponse").append("{")
         .append("messageId=").append(messageId)
         .append(", operation=").append(operation)
         .append(", status=").append(status)
         .append(", data=").append(if (data == None) "null" else Util.printArray(data.get, true))
         .append(", version=").append(version)
         .append("}").toString
   }
}

class ErrorResponse(override val messageId: Long, override val cacheName: String,
                    override val clientIntel: Short, override val status: OperationStatus,
                    override val topologyId: Int, val msg: String)
      extends Response(messageId, cacheName, clientIntel, ErrorResponse, status, topologyId) {
   override def toString = {
      new StringBuilder().append("ErrorResponse").append("{")
         .append("messageId=").append(messageId)
         .append(", operation=").append(operation)
         .append(", status=").append(status)
         .append(", msg=").append(msg)
         .append("}").toString
   }
}

class StatsResponse(override val messageId: Long, override val cacheName: String,
                    override val clientIntel: Short, val stats: Map[String, String],
                    override val topologyId: Int)
      extends Response(messageId, cacheName, clientIntel, StatsResponse, Success, topologyId) {
   override def toString = {
      new StringBuilder().append("StatsResponse").append("{")
         .append("messageId=").append(messageId)
         .append(", stats=").append(stats)
         .append("}").toString
   }
}

abstract class AbstractTopologyResponse(val view: TopologyView)

case class TopologyAwareResponse(override val view: TopologyView)
      extends AbstractTopologyResponse(view)

case class HashDistAwareResponse(override val view: TopologyView, numOwners: Int, hashFunction: Byte, hashSpace: Int)
      extends AbstractTopologyResponse(view)
