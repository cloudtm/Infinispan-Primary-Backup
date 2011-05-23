/*
 * JBoss, Home of Professional Open Source
 * Copyright 2008, Red Hat Middleware LLC, and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.infinispan.util.concurrent.locks;

import org.infinispan.config.Configuration;
import org.infinispan.container.entries.CacheEntry;
import org.infinispan.context.Flag;
import org.infinispan.context.InvocationContext;
import org.infinispan.context.InvocationContextContainer;
import org.infinispan.context.impl.LocalTxInvocationContext;
import org.infinispan.context.impl.RemoteTxInvocationContext;
import org.infinispan.context.impl.TxInvocationContext;
import org.infinispan.factories.annotations.Inject;
import org.infinispan.factories.annotations.Start;
import org.infinispan.jmx.annotations.MBean;
import org.infinispan.jmx.annotations.ManagedAttribute;
import org.infinispan.jmx.annotations.ManagedOperation;
import org.infinispan.transaction.xa.GlobalTransaction;
import org.infinispan.util.GlobalHistogram;
import org.infinispan.util.ReversibleOrderedSet;
import org.infinispan.util.concurrent.locks.containers.*;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;
import org.rhq.helpers.pluginAnnotations.agent.DataType;
import org.rhq.helpers.pluginAnnotations.agent.Metric;
import org.rhq.helpers.pluginAnnotations.agent.Operation;

import javax.transaction.Transaction;
import javax.transaction.TransactionManager;
import java.util.Iterator;
import java.util.Map;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;

/**
 * Handles locks for the MVCC based LockingInterceptor
 *
 * @author Manik Surtani (<a href="mailto:manik@jboss.org">manik@jboss.org</a>)
 * @since 4.0
 */
@MBean(objectName = "LockManager", description = "Manager that handles MVCC locks for entries")
public class LockManagerImpl implements LockManager {
   protected Configuration configuration;
   protected LockContainer lockContainer;
   private TransactionManager transactionManager;
   private InvocationContextContainer invocationContextContainer;
   private static final Log log = LogFactory.getLog(LockManagerImpl.class);
   protected static final boolean trace = log.isTraceEnabled();
   private static final String ANOTHER_THREAD = "(another thread)";

   /**
    * //DIE
    * These atomic counters give the information about the lock contention occurrence and nature
    */

    private AtomicLong localLocalContentions=new AtomicLong(0);
    private AtomicLong localRemoteContentions=new AtomicLong(0);
    private AtomicLong remoteLocalContentions=new AtomicLong(0);
    private AtomicLong remoteRemoteContentions=new AtomicLong(0);
    private AtomicLong committedTimeWaitedOnLocks=new AtomicLong(0);

    private AtomicLong commitedLocalTx=new AtomicLong(0);
    private AtomicLong commitedRemoteTx = new AtomicLong(0);
    private AtomicLong remoteHoldTime = new AtomicLong(0);
    private AtomicLong localHoldTime = new AtomicLong(0);
    private AtomicLong suxRemoteHoldTime = new AtomicLong(0);
    private AtomicLong suxLocalHoldTime = new AtomicLong(0);
    private AtomicLong holdLocalTransaction = new AtomicLong(0);
    private AtomicLong abortedLocalTransaction = new AtomicLong(0);
    private AtomicLong abortedRemoteTransaction = new AtomicLong(0);

    private GlobalHistogram histo = new GlobalHistogram(1,10000,10);

   @Inject
   public void injectDependencies(Configuration configuration, TransactionManager transactionManager, InvocationContextContainer invocationContextContainer) {
      this.configuration = configuration;
      this.transactionManager = transactionManager;
      this.invocationContextContainer = invocationContextContainer;
   }

   @Start
   public void startLockManager() {
      lockContainer = configuration.isUseLockStriping() ?                                                                                                                                                                                                 //SEB
      transactionManager == null ? new ReentrantStripedLockContainer(configuration.getConcurrencyLevel()) : configuration.isSwitchEnabled() ? new OwnableReentrantStubbornStripedLockContainer(configuration.getConcurrencyLevel(), invocationContextContainer) : new OwnableReentrantStripedLockContainer(configuration.getConcurrencyLevel(), invocationContextContainer) :
      transactionManager == null ? new ReentrantPerEntryLockContainer(configuration.getConcurrencyLevel()) : configuration.isSwitchEnabled() ? new OwnableReentrantStubbornPerEntryLockContainer(configuration.getConcurrencyLevel(), invocationContextContainer) : new OwnableReentrantPerEntryLockContainer(configuration.getConcurrencyLevel(), invocationContextContainer);
   }

   public boolean lockAndRecord(Object key, InvocationContext ctx) throws InterruptedException {
      //DIE
      long start_lock_timer=0;
      long time_to_acquire_lock=0;
      if(ctx.isInTxScope()){
         this.updateContentionStats(key,ctx);
      }


      //SEB
      long lockTimeout = ((ctx instanceof RemoteTxInvocationContext) && configuration.isSwitchEnabled() && ((RemoteTxInvocationContext) ctx).getReplicasPolicyMode() == Configuration.ReplicasPolicyMode.PASSIVE_REPLICATION)? -1 : getLockAcquisitionTimeout(ctx);
      if (trace) log.trace("Attempting to lock %s with acquisition timeout of %s millis", key, lockTimeout);

      start_lock_timer=System.nanoTime();  //DIE

      if (lockContainer.acquireLock(key, lockTimeout, MILLISECONDS) != null) {


          // successfully locked!

          //DIE
         if(ctx.isInTxScope() && ctx.isOriginLocal()){
            time_to_acquire_lock=System.nanoTime()-start_lock_timer;
             LocalTxInvocationContext lctx=(LocalTxInvocationContext)ctx;
             lctx.addWaitedTimeOnLocks(time_to_acquire_lock);
         }



         if (ctx instanceof TxInvocationContext) {
            TxInvocationContext tctx = (TxInvocationContext) ctx;
            if (!tctx.isRunningTransactionValid()) {
               Transaction tx = tctx.getRunningTransaction();
               log.debug("Successfully acquired lock, but the transaction %s is no longer valid!  Releasing lock.", tx);
               lockContainer.releaseLock(key);
               throw new IllegalStateException("Transaction "+tx+" appears to no longer be valid!");
            }
         }
         if (trace) log.trace("Successfully acquired lock!");         
         return true;
      }

      // couldn't acquire lock!
      return false;
   }

   protected long getLockAcquisitionTimeout(InvocationContext ctx) {
      return ctx.hasFlag(Flag.ZERO_LOCK_ACQUISITION_TIMEOUT) ?
            0 : configuration.getLockAcquisitionTimeout();
   }

   public void unlock(Object key) {
      if (trace) log.trace("Attempting to unlock " + key);
      lockContainer.releaseLock(key);
   }


   //DiE
    public long holdTime(Object key){
        return lockContainer.holdTime(key);

    }

   @SuppressWarnings("unchecked")
   public void unlock(InvocationContext ctx) {
      ReversibleOrderedSet<Map.Entry<Object, CacheEntry>> entries = ctx.getLookedUpEntries().entrySet();
      if (!entries.isEmpty()) {
         // unlocking needs to be done in reverse order.
         Iterator<Map.Entry<Object, CacheEntry>> it = entries.reverseIterator();
         while (it.hasNext()) {
            Map.Entry<Object, CacheEntry> e = it.next();
            CacheEntry entry = e.getValue();
            if (possiblyLocked(entry)) {
               // has been locked!
               Object k = e.getKey();
               if (trace) log.trace("Attempting to unlock " + k);
               lockContainer.releaseLock(k);

            }
         }
      }
   }

   public boolean ownsLock(Object key, Object owner) {
      return lockContainer.ownsLock(key, owner);
   }

   public boolean isLocked(Object key) {
      return lockContainer.isLocked(key);
   }

   public Object getOwner(Object key) {
      if (lockContainer.isLocked(key)) {
         Lock l = lockContainer.getLock(key);

         if (l instanceof OwnableReentrantLock) {
            return ((OwnableReentrantLock) l).getOwner();
         } else {
            // cannot determine owner, JDK Reentrant locks only provide best-effort guesses.
            return ANOTHER_THREAD;
         }
      } else return null;
   }

   public String printLockInfo() {
      return lockContainer.toString();
   }

   public final boolean possiblyLocked(CacheEntry entry) {
      return entry == null || entry.isChanged() || entry.isNull() || entry.isLockPlaceholder();
   }

   public void releaseLocks(InvocationContext ctx) {
      Object owner = ctx.getLockOwner();
      int heldCount=0;
      // clean up.
      // unlocking needs to be done in reverse order.
      ReversibleOrderedSet<Map.Entry<Object, CacheEntry>> entries = ctx.getLookedUpEntries().entrySet();
      Iterator<Map.Entry<Object, CacheEntry>> it = entries.reverseIterator();
      if (trace) log.trace("Number of entries in context: %s", entries.size());

      while (it.hasNext()) {
         Map.Entry<Object, CacheEntry> e = it.next();
         CacheEntry entry = e.getValue();
         Object key = e.getKey();
         boolean needToUnlock = possiblyLocked(entry);
         // could be null with read-committed
         if (entry != null && entry.isChanged()) entry.rollback();
         else {
            if (trace) log.trace("Entry for key %s is null, not calling rollbackUpdate", key);
         }
         // and then unlock
         if (needToUnlock) {
            //DIE
            if(ctx.isInTxScope() && lockContainer.ownsLock(key,owner)){
               log.warn("Abort: Releasing lock on [" + key + "] for owner " + owner);
               heldCount++;
               ((TxInvocationContext)ctx).addAbortedHoldTime(this.holdTime(key));
            }
            unlock(key);
         }
      }
        if(ctx.isInTxScope() && heldCount!=0){
           log.warn("ABORT: preHoldTime "+((TxInvocationContext)ctx).getAbortedHoldTime()+" postHoldTime "+(((TxInvocationContext)ctx).getAbortedHoldTime()/heldCount));
           ((TxInvocationContext)ctx).averageAbortedHoldTime(heldCount);
        }
   }

   @ManagedAttribute(description = "The concurrency level that the MVCC Lock Manager has been configured with.")
   @Metric(displayName = "Concurrency level", dataType = DataType.TRAIT)
   public int getConcurrencyLevel() {
      return configuration.getConcurrencyLevel();
   }

   @ManagedAttribute(description = "The number of exclusive locks that are held.")
   @Metric(displayName = "Number of locks held")
   public int getNumberOfLocksHeld() {
      return lockContainer.getNumLocksHeld();
   }

   @ManagedAttribute(description = "The number of exclusive locks that are available.")
   @Metric(displayName = "Number of locks available")
   public int getNumberOfLocksAvailable() {
      return lockContainer.size() - lockContainer.getNumLocksHeld();
   }

   //DIE
   public void updateWaitedTimeOnLockStats(long waitedTime){
        this.committedTimeWaitedOnLocks.addAndGet(waitedTime);
        this.commitedLocalTx.incrementAndGet();
       //System.out.println(commitedLocalTx.get());
   }

   //DIE
   private void updateContentionStats(Object key, InvocationContext ctx){
      GlobalTransaction holder=(GlobalTransaction)this.getOwner(key);
      if(holder!=null){
         GlobalTransaction me=(GlobalTransaction) ctx.getLockOwner();
         if(holder!=me){
            boolean amILocal=!(me.isRemote());
            boolean isItLocal=!(holder.isRemote());
            if(amILocal && isItLocal )
               this.localLocalContentions.incrementAndGet();
            else if(amILocal && !isItLocal)
               this.localRemoteContentions.incrementAndGet();
            else if(!amILocal && isItLocal)
               this.remoteLocalContentions.incrementAndGet();
            else
               this.remoteRemoteContentions.incrementAndGet();
         }
      }

   }


    public void insertSample(Object key, long time){
        this.histo.insertSample(time,key);
    }

    @ManagedOperation
    public void dumpHistogram(){
        this.histo.dumpHistogram();
    }


   public void updateHoldTime(TxInvocationContext ctx, long hold,boolean commit){
       if(ctx.isOriginLocal()){
           this.localHoldTime.addAndGet(hold);
           if(commit){
               this.holdLocalTransaction.incrementAndGet();
               this.suxLocalHoldTime.addAndGet(hold);
           }
           else{
               this.abortedLocalTransaction.incrementAndGet();
           }
       }
       else{
           this.remoteHoldTime.addAndGet(hold);
           if(commit){
              this.commitedRemoteTx.incrementAndGet();
              this.suxRemoteHoldTime.addAndGet(hold);
           }
           else{
               this.abortedRemoteTransaction.incrementAndGet();
           }
       }

   }

    @ManagedAttribute(description = "Average local lock hold time" )
    @Metric(displayName = "LocalHoldTime")
    public long getLocalHoldTime(){
        if(holdLocalTransaction.get()==0)
            return 0;
        return this.localHoldTime.get()/holdLocalTransaction.get();
    }

    @ManagedAttribute(description = "Average local lock hold time of a successful tx")
    @Metric(displayName = "SuxLocalHoldTime")
    public long getSuxLocalHoldTime(){
        if(holdLocalTransaction.get()==0)
            return 0;
        return this.suxLocalHoldTime.get()/holdLocalTransaction.get();
    }


    @ManagedAttribute(description="Average remote lock hold time of a successgul tx")
    @Metric(displayName = "suxRemoteHoldTime")
    public long getSuxRemoteHoldTime(){
        if(commitedRemoteTx.get()==0)
            return 0;
        return this.suxRemoteHoldTime.get()/commitedRemoteTx.get();
    }




    @ManagedAttribute(description = "Average remote lock hold time" )
    @Metric(displayName = "RemoteHoldTime")
    public long getRemoteHoldTime(){
        if(commitedRemoteTx.get()==0)
            return 0;
        return this.remoteHoldTime.get()/commitedRemoteTx.get();
    }

    @ManagedAttribute(description = "Average lock hold time" )
    @Metric(displayName = "HoldTime")
    public long getHoldTime(){
        if(commitedRemoteTx.get()+holdLocalTransaction.get()==0)
            return 0;
        return (this.localHoldTime.get()+this.remoteHoldTime.get())/(this.holdLocalTransaction.get()+this.commitedRemoteTx.get());
    }
    @ManagedAttribute(description = "Average lock hold time of a successful transaction")
    @Metric(displayName = "SuxHoldTime")
    public long getSuxHoldTime(){
        if(commitedRemoteTx.get()+holdLocalTransaction.get()==0)
                    return 0;
        return (this.suxLocalHoldTime.get()+this.suxRemoteHoldTime.get())/(this.holdLocalTransaction.get()+this.commitedRemoteTx.get());

    }

    @ManagedAttribute(description = "Hold time averaged both on committed and aborted tx")
    @Metric(displayName = "AvgHoldTime")
    public long getAvgHoldTime(){

        return (this.remoteHoldTime.get()+this.localHoldTime.get())/(this.holdLocalTransaction.get()+this.commitedRemoteTx.get()+this.abortedLocalTransaction.get()+this.abortedRemoteTransaction.get());
    }




   //DIE
   @ManagedAttribute(description = "The number of contentions among local transactions")
   @Metric(displayName = "LocalLocalContentions")
    public long getLocalLocalContentions(){
       return this.localLocalContentions.get();
    }
    //DIE
    @ManagedAttribute(description = "The number of contentions among local transactions with remote ones")
    @Metric(displayName = "LocalRemoteContentions")
    public long getLocalRemoteContentions(){
        return this.localRemoteContentions.get();
    }
    //DIE
    @ManagedAttribute(description = "The number of contentions among remote transactions and local ones")
    @Metric(displayName = "RemoteLocalContentions")
    public long getRemoteLocalContentions(){
         return this.remoteLocalContentions.get();
    }
    //DIE
    @ManagedAttribute(description = "The number of contentions among remote transactions")
    @Metric(displayName = "RemoteRemoteContentions")
    public long getRemoteRemoteContentions(){
       return this.remoteRemoteContentions.get();
    }


    //DIE
    @ManagedAttribute(description = "Average time spent waiting for locks during the execution of a transaction (committed txs only)")
    @Metric(displayName = "AvgCommittedTimeWaitedOnLocks")
    public long getAvgCommittedTimeWaitedOnLocks(){
        if(this.commitedLocalTx.get()==0 || this.committedTimeWaitedOnLocks.get()==0)
            return 0;
        return this.committedTimeWaitedOnLocks.get()/this.commitedLocalTx.get();
    }

    @ManagedOperation(description = "Reset committed waited time on locks")
    public void resetAvgCommittedTimeWaitedLocks(){
        this.committedTimeWaitedOnLocks.set(0);
        this.commitedLocalTx.set(0);
    }

   @ManagedOperation(description = "Resets statistics relevant to contentions")
   @Operation(displayName = "Reset Local Transactions' contentions")
   public void resetLocalTxContentions(){
       this.localLocalContentions.set(0);
       this.localRemoteContentions.set(0);	

   }
}
