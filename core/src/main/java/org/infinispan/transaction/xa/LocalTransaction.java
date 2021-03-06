package org.infinispan.transaction.xa;

import org.infinispan.commands.write.WriteCommand;
import org.infinispan.config.Configuration;
import org.infinispan.container.entries.CacheEntry;
import org.infinispan.remoting.transport.Address;
import org.infinispan.util.BidirectionalLinkedHashMap;
import org.infinispan.util.BidirectionalMap;
import org.infinispan.util.InfinispanCollections;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

import javax.sound.midi.SysexMessage;
import javax.transaction.Transaction;
import javax.transaction.xa.Xid;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * @author Mircea.Markus@jboss.com
 * @since 4.2
 */
public class LocalTransaction extends AbstractCacheTransaction {

   private static Log log = LogFactory.getLog(LocalTransaction.class);
   private static final boolean trace = log.isTraceEnabled();


   /**
   * //DIE: I don't put this variable also in the parent class, because it is needed only in local txs
   */
   private long waited_time_on_locks=0;
   private long lifeTime=0;
   private long commitTime=0;
   private boolean alreadyWritten=false;

   private Set<Address> remoteLockedNodes;

   /** mark as volatile as this might be set from the tx thread code on view change*/
   private volatile boolean isMarkedForRollback;

   private final Transaction transaction;
   private Xid xid;

   //SEB
   private Configuration.ReplicasPolicyMode forcedReplicatedCommit;

   public LocalTransaction(Transaction transaction, GlobalTransaction tx) {
      super.tx = tx;
      this.transaction = transaction;
   }

   public void addModification(WriteCommand mod) {
      if (trace) log.trace("Adding modification %s. Mod list is %s", mod, modifications);
      if (modifications == null) {
         modifications = new LinkedList<WriteCommand>();
      }
      modifications.add(mod);
   }

   public boolean hasRemoteLocksAcquired(List<Address> leavers) {
      if (log.isTraceEnabled()) {
         log.trace("My remote locks: " + remoteLockedNodes + ", leavers are:" + leavers);
      }
      return (remoteLockedNodes != null) && !Collections.disjoint(remoteLockedNodes, leavers);
   }

   public void locksAcquired(Collection<Address> nodes) {
      if (remoteLockedNodes == null) remoteLockedNodes = new HashSet<Address>();
      remoteLockedNodes.addAll(nodes);
   }
   
   public Collection<Address> getRemoteLocksAcquired(){
	   if (remoteLockedNodes == null) return Collections.emptySet();
	   return remoteLockedNodes;
   }

   public void markForRollback() {
      isMarkedForRollback = true;
   }

   public boolean isMarkedForRollback() {
      return isMarkedForRollback;
   }

   public Transaction getTransaction() {
      return transaction;
   }

   public BidirectionalMap<Object, CacheEntry> getLookedUpEntries() {
      return (BidirectionalMap<Object, CacheEntry>)
            (lookedUpEntries == null ? InfinispanCollections.emptyBidirectionalMap() : lookedUpEntries);
   }

   public void putLookedUpEntry(Object key, CacheEntry e) {
      if (lookedUpEntries == null) lookedUpEntries = new BidirectionalLinkedHashMap<Object, CacheEntry>(4);
      lookedUpEntries.put(key, e);
   }

   public boolean isReadOnly() {
      return (modifications == null || modifications.isEmpty()) && (lookedUpEntries == null || lookedUpEntries.isEmpty());
   }

   public void setXid(Xid xid) {
      this.xid = xid;
   }

   public Xid getXid() {
      return xid;
   }

   /**
    * As per the JTA spec, XAResource.start is called on enlistment. That method also sets the xid for this local
    * transaction.
    */
   public boolean isEnlisted() {
      return xid != null;
   }

   //SEB
   public void setForcedReplicatedCommit(Configuration.ReplicasPolicyMode forcedReplicatedCommit) {
      this.forcedReplicatedCommit = forcedReplicatedCommit;
   }
   //SEB
   public Configuration.ReplicasPolicyMode getForcedReplicatedCommit() {
      return forcedReplicatedCommit;
   }
   //DIE
   public void addWaitedTimeOnLocks(long delta){
      this.waited_time_on_locks+=delta;
   }
   //DIE
   public long getWaitedTimeOnLocks(){
      return this.waited_time_on_locks;
   }

    public void startTimer(){
        this.lifeTime = System.nanoTime();
    }

    public long getLifeTime(){
       return this.lifeTime=System.nanoTime()-lifeTime;
    }

    public boolean hasAlreadyWritten(){
       return this.alreadyWritten;
    }

    public void setAlreadyWritten(){
        this.alreadyWritten=true;
    }

    public void startCommitTime(){
        this.commitTime=System.nanoTime();
    }

    public long getCommitTime(){
        return (this.commitTime=this.commitTime- System.nanoTime());
    }
}
