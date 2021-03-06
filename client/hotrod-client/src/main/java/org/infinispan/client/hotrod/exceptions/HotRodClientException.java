package org.infinispan.client.hotrod.exceptions;

/**
 * Base class for exceptions reported by the hot rod client.
 *
 * @author Mircea.Markus@jboss.com
 * @since 4.1
 */
public class HotRodClientException extends RuntimeException {
   private long messageId = -1;
   private int errorStatusCode = -1;

   public HotRodClientException() {
   }

   public HotRodClientException(String message) {
      super(message);
   }

   public HotRodClientException(Throwable cause) {
      super(cause);
   }

   public HotRodClientException(String message, Throwable cause) {
      super(message, cause);
   }

   public HotRodClientException(String remoteMessage, long messageId, int errorStatusCode) {
      super(remoteMessage);
      this.messageId = messageId;
      this.errorStatusCode = errorStatusCode;
   }


   @Override
   public String toString() {
      StringBuilder sb = new StringBuilder(getClass().getName());
      sb.append(":");
      if (messageId != -1) sb.append(" id [").append(messageId).append("]");
      if (errorStatusCode != -1) sb.append(" code [").append(errorStatusCode).append("]");
      String message = getLocalizedMessage();
      if (message != null) sb.append(" ").append(message);
      return sb.toString();
   }
}
