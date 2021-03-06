package org.infinispan;

/**
 * @author Sebastiano Peluso
 * @author Diego Didona
 * @since 5.0
 */
//SEBDIE
public class PassiveReplicationException extends CacheException{

    public PassiveReplicationException() {
      super();
   }

   public PassiveReplicationException(Throwable cause) {
      super(cause);
   }

   public PassiveReplicationException(String msg) {
      super(msg);
   }

   public PassiveReplicationException(String msg, Throwable cause) {
      super(msg, cause);
   }
}
