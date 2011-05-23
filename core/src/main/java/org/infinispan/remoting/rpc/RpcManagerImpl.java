package org.infinispan.remoting.rpc;

import org.infinispan.CacheException;
import org.infinispan.commands.CommandsFactory;
import org.infinispan.commands.ReplicableCommand;
import org.infinispan.commands.remote.CacheRpcCommand;
import org.infinispan.commands.tx.CommitCommand;
import org.infinispan.commands.tx.PassiveReplicationCommand;
import org.infinispan.commands.tx.PrepareCommand;
import org.infinispan.config.Configuration;
import org.infinispan.factories.KnownComponentNames;
import org.infinispan.factories.annotations.ComponentName;
import org.infinispan.factories.annotations.Inject;
import org.infinispan.factories.annotations.Start;
import org.infinispan.jmx.annotations.MBean;
import org.infinispan.jmx.annotations.ManagedAttribute;
import org.infinispan.jmx.annotations.ManagedOperation;
import org.infinispan.marshall.exts.ReplicableCommandExternalizer;
import org.infinispan.remoting.ReplicationException;
import org.infinispan.remoting.ReplicationQueue;
import org.infinispan.remoting.responses.ExtendedResponse;
import org.infinispan.remoting.responses.Response;
import org.infinispan.remoting.responses.SuccessfulResponse;
import org.infinispan.remoting.transport.Address;
import org.infinispan.remoting.transport.Transport;
import org.infinispan.statetransfer.StateTransferException;
import org.infinispan.util.concurrent.NotifyingNotifiableFuture;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;
import org.jboss.marshalling.ObjectOutputStreamMarshaller;
import org.rhq.helpers.pluginAnnotations.agent.DataType;
import org.rhq.helpers.pluginAnnotations.agent.DisplayType;
import org.rhq.helpers.pluginAnnotations.agent.MeasurementType;
import org.rhq.helpers.pluginAnnotations.agent.Metric;
import org.rhq.helpers.pluginAnnotations.agent.Operation;
import org.rhq.helpers.pluginAnnotations.agent.Parameter;
import org.rhq.helpers.pluginAnnotations.agent.Units;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import org.jboss.marshalling.Marshaller;


import java.text.NumberFormat;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;

/**
 * This component really is just a wrapper around a {@link org.infinispan.remoting.transport.Transport} implementation,
 * and is used to set up the transport and provide lifecycle and dependency hooks into external transport
 * implementations.
 *
 * @author Manik Surtani
 * @author Galder Zamarreño
 * @author Mircea.Markus@jboss.com
 * @since 4.0
 */
@MBean(objectName = "RpcManager", description = "Manages all remote calls to remote cache instances in the cluster.")
public class RpcManagerImpl implements RpcManager {

   private static final Log log = LogFactory.getLog(RpcManagerImpl.class);
   private static final boolean trace = log.isTraceEnabled();

   private Transport t;
   private final AtomicLong replicationCount = new AtomicLong(0);
   private final AtomicLong replicationFailures = new AtomicLong(0);
   private final AtomicLong totalReplicationTime = new AtomicLong(0);
   //SEBDIE
   private final AtomicLong committedReplicationCount = new AtomicLong(0);
   private final AtomicLong txReplicationTime = new AtomicLong(0);
   private final AtomicLong txSuccessfulReplicationTime=new AtomicLong(0);
   private final AtomicLong successfulRTT= new AtomicLong(0);
   private final AtomicLong avgReplayTime = new AtomicLong(0);      //Time spent to replay modifications (based on piggybacked info)
   private final AtomicLong avgSuxReplayTime = new AtomicLong(0);   //Time spent to replay successful modifications


   @ManagedAttribute(description = "Enables or disables the gathering of statistics by this component", writable = true)
   boolean statisticsEnabled = false; // by default, don't gather statistics.
   private volatile Address currentStateTransferSource;
   private boolean stateTransferEnabled;
   private Configuration configuration;
   private ReplicationQueue replicationQueue;
   private ExecutorService asyncExecutor;
   private CommandsFactory cf;


   @Inject
   public void injectDependencies(Transport t, Configuration configuration, ReplicationQueue replicationQueue, CommandsFactory cf,
                                  @ComponentName(KnownComponentNames.ASYNC_TRANSPORT_EXECUTOR) ExecutorService e) {
      this.t = t;
      this.configuration = configuration;
      this.replicationQueue = replicationQueue;
      this.asyncExecutor = e;
      this.cf = cf;
   }

   @Start(priority = 9)
   private void start() {
      stateTransferEnabled = configuration.isStateTransferEnabled();
      statisticsEnabled = configuration.isExposeJmxStatistics();
   }

   private boolean useReplicationQueue(boolean sync) {
      return !sync && replicationQueue != null && replicationQueue.isEnabled();
   }

   public final List<Response> invokeRemotely(Collection<Address> recipients, ReplicableCommand rpcCommand, ResponseMode mode, long timeout, boolean usePriorityQueue, ResponseFilter responseFilter) {
      List<Address> members = t.getMembers();
      byte rpcId=rpcCommand.getCommandId();
      long commandSize;
      boolean exception_thrown=false;
      List<Response> result=null;

      if (members.size() < 2) {
         if (log.isDebugEnabled())
            log.debug("We're the only member in the cluster; Don't invoke remotely.");
         return Collections.emptyList();
      } else {
         long startTime = 0;
         if (statisticsEnabled){
            startTime = System.nanoTime();
         }
         try {
            result = t.invokeRemotely(recipients, rpcCommand, mode, timeout, usePriorityQueue, responseFilter, stateTransferEnabled);

            return result;
         } catch (CacheException e) {
            exception_thrown=true;
            if (log.isTraceEnabled()) {
               log.trace("replication exception: ", e);
            }

            if (isStatisticsEnabled()){
               replicationFailures.incrementAndGet();
            }
            throw e;
         } catch (Throwable th) {
            exception_thrown=true;
            log.error("unexpected error while replicating", th);
            if (isStatisticsEnabled()) replicationFailures.incrementAndGet();
            throw new CacheException(th);
         } finally {
            if (statisticsEnabled) {
               long timeTaken = System.nanoTime() - startTime;
               totalReplicationTime.getAndAdd(timeTaken);
               /*SEBDIE
                The CommitCommand is not considered since by default ISPN has the async commit phase
                (i.e. it sends the commit command to the cohorts and does not wait for acks)
                We don't put an explicit condition to consider the non-default case since the model
                is designed to work with the async commit
                */
               if(rpcCommand instanceof PrepareCommand || rpcCommand instanceof PassiveReplicationCommand){
                  txReplicationTime.getAndAdd(timeTaken);
                  if(!exception_thrown){
                     txSuccessfulReplicationTime.getAndAdd(timeTaken);        //time spent in transport layer
                     long maxReplayTime= getMaxReplayTime(result);
                     avgSuxReplayTime.addAndGet(maxReplayTime);
                     avgReplayTime.addAndGet(maxReplayTime);
                     committedReplicationCount.incrementAndGet();
                     this.successfulRTT.getAndAdd(timeTaken-maxReplayTime);
               }
                   else{
                     avgReplayTime.addAndGet(timeout*1000000); //timeout is in millis, stats are nanos
                  }

              }
          }
       }
   }
}
   public final List<Response> invokeRemotely(Collection<Address> recipients, ReplicableCommand rpcCommand, ResponseMode mode, long timeout, boolean usePriorityQueue) {
      return invokeRemotely(recipients, rpcCommand, mode, timeout, usePriorityQueue, null);
   }

   public final List<Response> invokeRemotely(Collection<Address> recipients, ReplicableCommand rpcCommand, ResponseMode mode, long timeout) throws Exception {
      return invokeRemotely(recipients, rpcCommand, mode, timeout, false, null);
   }

   public void retrieveState(String cacheName, long timeout) throws StateTransferException {
      if (t.isSupportStateTransfer()) {
         long initialWaitTime = configuration.getStateRetrievalInitialRetryWaitTime();
         int waitTimeIncreaseFactor = configuration.getStateRetrievalRetryWaitTimeIncreaseFactor();
         int numRetries = configuration.getStateRetrievalNumRetries();
         List<Address> members = t.getMembers();
         if (members.size() < 2) {
            if (log.isDebugEnabled())
               log.debug("We're the only member in the cluster; no one to retrieve state from. Not doing anything!");
            return;
         }

         boolean success = false;

         try {
            long wait = initialWaitTime;
            outer:
            for (int i = 0; i < numRetries; i++) {
               for (Address member : members) {
                  if (!member.equals(t.getAddress())) {
                     try {
                        if (log.isInfoEnabled()) log.info("Trying to fetch state from %s", member);
                        currentStateTransferSource = member;
                        if (t.retrieveState(cacheName, member, timeout)) {
                           if (log.isInfoEnabled())
                              log.info("Successfully retrieved and applied state from %s", member);
                           success = true;
                           break outer;
                        }
                     } catch (StateTransferException e) {
                        if (log.isDebugEnabled()) log.debug("Error while fetching state from member " + member, e);
                     } finally {
                        currentStateTransferSource = null;
                     }
                  }
               }

               if (!success) {
                  if (log.isWarnEnabled())
                     log.warn("Could not find available peer for state, backing off and retrying");

                  try {
                     Thread.sleep(wait *= waitTimeIncreaseFactor);
                  }
                  catch (InterruptedException e) {
                     Thread.currentThread().interrupt();
                  }
               }

            }
         } finally {
            currentStateTransferSource = null;
         }

         if (!success) throw new StateTransferException("Unable to fetch state on startup");
      } else {
         throw new StateTransferException("Transport does not, or is not configured to, support state transfer.  Please disable fetching state on startup, or reconfigure your transport.");
      }
   }

   public final void broadcastRpcCommand(ReplicableCommand rpc, boolean sync) throws ReplicationException {
      broadcastRpcCommand(rpc, sync, false);
   }

   public final void broadcastRpcCommand(ReplicableCommand rpc, boolean sync, boolean usePriorityQueue) throws ReplicationException {
      if (useReplicationQueue(sync)) {
         replicationQueue.add(rpc);
      } else {
         invokeRemotely(null, rpc, sync, usePriorityQueue);
      }
   }

   public final void broadcastRpcCommandInFuture(ReplicableCommand rpc, NotifyingNotifiableFuture<Object> l) {
      broadcastRpcCommandInFuture(rpc, false, l);
   }

   public final void broadcastRpcCommandInFuture(ReplicableCommand rpc, boolean usePriorityQueue, NotifyingNotifiableFuture<Object> l) {
      invokeRemotelyInFuture(null, rpc, usePriorityQueue, l);
   }

   public final void invokeRemotely(Collection<Address> recipients, ReplicableCommand rpc, boolean sync) throws ReplicationException {
      invokeRemotely(recipients, rpc, sync, false);
   }

   public final List<Response> invokeRemotely(Collection<Address> recipients, ReplicableCommand rpc, boolean sync, boolean usePriorityQueue) throws ReplicationException {
      return invokeRemotely(recipients, rpc, sync, usePriorityQueue, configuration.getSyncReplTimeout());
   }

   public final List<Response> invokeRemotely(Collection<Address> recipients, ReplicableCommand rpc, boolean sync, boolean usePriorityQueue, long timeout) throws ReplicationException {
      if (trace) log.trace("%s broadcasting call %s to recipient list %s", t.getAddress(), rpc, recipients);

      if (useReplicationQueue(sync)) {
         replicationQueue.add(rpc);
         return null;
      } else {
         if (!(rpc instanceof CacheRpcCommand)) {
            rpc = cf.buildSingleRpcCommand(rpc);
         }
         List<Response> rsps = invokeRemotely(recipients, rpc, getResponseMode(sync), timeout, usePriorityQueue);
         if (trace) log.trace("responses=" + rsps);
         if (sync) checkResponses(rsps);
         return rsps;
      }
   }

   public final void invokeRemotelyInFuture(Collection<Address> recipients, ReplicableCommand rpc, NotifyingNotifiableFuture<Object> l) {
      invokeRemotelyInFuture(recipients, rpc, false, l);
   }

   public final void invokeRemotelyInFuture(final Collection<Address> recipients, final ReplicableCommand rpc, final boolean usePriorityQueue, final NotifyingNotifiableFuture<Object> l) {
      invokeRemotelyInFuture(recipients, rpc, usePriorityQueue, l, configuration.getSyncReplTimeout());
   }

   public final void invokeRemotelyInFuture(final Collection<Address> recipients, final ReplicableCommand rpc, final boolean usePriorityQueue, final NotifyingNotifiableFuture<Object> l, final long timeout) {
      if (trace) log.trace("%s invoking in future call %s to recipient list %s", t.getAddress(), rpc, recipients);
      Callable<Object> c = new Callable<Object>() {
         public Object call() {
            invokeRemotely(recipients, rpc, true, usePriorityQueue, timeout);
            l.notifyDone();
            return null;
         }
      };
      l.setNetworkFuture(asyncExecutor.submit(c));
   }

   public Transport getTransport() {
      return t;
   }

   public Address getCurrentStateTransferSource() {
      return currentStateTransferSource;
   }

   private ResponseMode getResponseMode(boolean sync) {
      return sync ? ResponseMode.SYNCHRONOUS : ResponseMode.getAsyncResponseMode(configuration);
   }

   /**
    * Checks whether any of the responses are exceptions. If yes, re-throws them (as exceptions or runtime exceptions).
    */
   private void checkResponses(List rsps) {
      if (rsps != null) {
         for (Object rsp : rsps) {
            if (rsp != null && rsp instanceof Throwable) {
               // lets print a stack trace first.
               Throwable throwable = (Throwable) rsp;
               if (trace) {
                  log.trace("Received Throwable from remote cache", throwable);
               }
               throw new ReplicationException(throwable);
            }
         }
      }
   }

   // -------------------------------------------- JMX information -----------------------------------------------

   @ManagedOperation(description = "Resets statistics gathered by this component")
   @Operation(displayName = "Reset statistics")
   public void resetStatistics() {
      replicationCount.set(0);
      replicationFailures.set(0);
      totalReplicationTime.set(0);
      //SEBDIE
      txReplicationTime.set(0);
      committedReplicationCount.set(0);
   }

   @ManagedAttribute(description = "Number of successfully replicated transactions")
   @Metric(displayName = "Number of successfully replicated transactions")
   public long getCommittedReplicationCount(){
      if (!isStatisticsEnabled()) {
         return -1;
      }
      return committedReplicationCount.get();
   }

   @ManagedAttribute(description = "Time spent in the Transport layer to replicate transactions (including remotely failed ones) ")
   @Metric(displayName = "Time spent in the Transport layer to replicate transactions (including remotely failed ones)")
   public long getTxReplicationTime(){
      if (!isStatisticsEnabled()) {
         return -1;
      }
      return txReplicationTime.get();

   }

   @ManagedAttribute(description = "Average time spent in the transport layer to replicate a transaction (aborted or completed)" )
   @Metric(displayName = "Avg transport-layer prepare time")
   public long getAvgPrepareTime(){
      if (!isStatisticsEnabled()){
         return -1;
      }
      if (committedReplicationCount.get()==0){
          return txReplicationTime.get();
      }
      return txReplicationTime.get()/committedReplicationCount.get();

   }
   
   @ManagedAttribute(description = "Average time spent in the transport layer to replicate successfully a transaction " )
   @Metric(displayName = "Avg transport-layer successful prepare time")
   public long getAvgSuccessfulPrepareTime(){
      if (committedReplicationCount.get()==0){
          return -1;
      }
      return txSuccessfulReplicationTime.get()/committedReplicationCount.get();

   }




   @ManagedAttribute(description = "Number of successful replications")
   @Metric(displayName = "Number of successful replications", measurementType = MeasurementType.TRENDSUP, displayType = DisplayType.SUMMARY)
   public long getReplicationCount() {
      if (!isStatisticsEnabled()) {
         return -1;
      }
      return replicationCount.get();
   }

   @ManagedAttribute(description = "Number of failed replications")
   @Metric(displayName = "Number of failed replications", measurementType = MeasurementType.TRENDSUP, displayType = DisplayType.SUMMARY)
   public long getReplicationFailures() {
      if (!isStatisticsEnabled()) {
         return -1;
      }
      return replicationFailures.get();
   }

   @Metric(displayName = "Statistics enabled", dataType = DataType.TRAIT)
   public boolean isStatisticsEnabled() {
      return statisticsEnabled;
   }

   @Operation(displayName = "Enable/disable statistics")
   public void setStatisticsEnabled(@Parameter(name = "enabled", description = "Whether statistics should be enabled or disabled (true/false)") boolean statisticsEnabled) {
      this.statisticsEnabled = statisticsEnabled;
   }

   @ManagedAttribute(description = "Successful replications as a ratio of total replications")
   public String getSuccessRatio() {
      if (replicationCount.get() == 0 || !statisticsEnabled) {
         return "N/A";
      }
      double ration = calculateSuccessRatio() * 100d;
      return NumberFormat.getInstance().format(ration) + "%";
   }

   @ManagedAttribute(description = "Successful replications as a ratio of total replications in numeric double format")
   @Metric(displayName = "Successful replication ratio", units = Units.PERCENTAGE, displayType = DisplayType.SUMMARY)
   public double getSuccessRatioFloatingPoint() {
      if (replicationCount.get() == 0 || !statisticsEnabled) return 0;
      return calculateSuccessRatio();
   }

   private double calculateSuccessRatio() {
      double totalCount = replicationCount.get() + replicationFailures.get();
      return replicationCount.get() / totalCount;
   }

   @ManagedAttribute(description = "The average time spent in the transport layer, in milliseconds")
   @Metric(displayName = "Average time spent in the transport layer", units = Units.MILLISECONDS, displayType = DisplayType.SUMMARY)
   public long getAverageReplicationTime() {
      if (replicationCount.get() == 0) {
         return 0;
      }
      return (totalReplicationTime.get()/1000000) / replicationCount.get();
   }


   @ManagedAttribute(description= "The average RTT time")
   public long getRTT(){
       if(this.committedReplicationCount.get()==0)
           return 0;
       return this.successfulRTT.get()/this.committedReplicationCount.get();
   }

   @ManagedOperation(description = "Reset transport layer custom statistics")
   @Operation(displayName = "Reset custom stats")
   public void resetTransportStats(){
      log.warn("Resetting Transport-layer statistics");
      //pre-existent ones
      this.replicationCount.set(0);
      this.replicationFailures.set(0);
      this.totalReplicationTime.set(0);
      //custom ones
      this.committedReplicationCount.set(0);
      this.txReplicationTime.set(0);
      this.txSuccessfulReplicationTime.set(0);
      this.successfulRTT.set(0);
      this.avgReplayTime.set(0);
      this.avgSuxReplayTime.set(0);
   }

    @ManagedAttribute(description = "Average successful replay time")
    public long getAvgReplayTime(){
        if(this.committedReplicationCount.get()==0){
            return 0;
        }
        return this.avgReplayTime.get()/committedReplicationCount.get();
    }

    @ManagedAttribute(description = "Average successful replay time")
    public long getSuccessfulAvgReplayTime(){
        if(this.committedReplicationCount.get()==0){
            return 0;
        }
        return this.avgSuxReplayTime.get()/committedReplicationCount.get();
    }




   // mainly for unit testing
   public void setTransport(Transport t) {
      this.t = t;
   }

   @Override
   public Address getAddress() {
      return t != null ? t.getAddress() : null;
   }



    private long getMaxReplayTime(List<Response> list){
        long max=0;
        ExtendedResponse temp;
        for(Response sux : list){
           temp=(ExtendedResponse)sux;
           if(temp.getReplayTime()>max){
               max=temp.getReplayTime();

           }
        }
        return max;
    }
}
