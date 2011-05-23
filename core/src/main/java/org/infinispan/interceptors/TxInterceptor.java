package org.infinispan.interceptors;

import org.infinispan.CacheException;
import org.infinispan.commands.CommandsFactory;
import org.infinispan.commands.VisitableCommand;
import org.infinispan.commands.control.LockControlCommand;
import org.infinispan.commands.read.GetKeyValueCommand;
import org.infinispan.commands.tx.CommitCommand;
import org.infinispan.commands.tx.PassiveReplicationCommand;
import org.infinispan.commands.tx.PrepareCommand;
import org.infinispan.commands.tx.RollbackCommand;
import org.infinispan.commands.write.ClearCommand;
import org.infinispan.commands.write.InvalidateCommand;
import org.infinispan.commands.write.PutKeyValueCommand;
import org.infinispan.commands.write.PutMapCommand;
import org.infinispan.commands.write.RemoveCommand;
import org.infinispan.commands.write.ReplaceCommand;
import org.infinispan.commands.write.WriteCommand;
import org.infinispan.config.Configuration;
import org.infinispan.context.Flag;
import org.infinispan.context.InvocationContext;
import org.infinispan.context.InvocationContextContainer;
import org.infinispan.context.impl.LocalTxInvocationContext;
import org.infinispan.context.impl.RemoteTxInvocationContext;
import org.infinispan.context.impl.TxInvocationContext;
import org.infinispan.factories.annotations.Inject;
import org.infinispan.interceptors.base.CommandInterceptor;
import org.infinispan.jmx.annotations.MBean;
import org.infinispan.jmx.annotations.ManagedAttribute;
import org.infinispan.jmx.annotations.ManagedOperation;
import org.infinispan.transaction.TransactionLog;
import org.infinispan.transaction.xa.LocalTransaction;
import org.infinispan.transaction.xa.TransactionTable;
import org.infinispan.transaction.xa.TransactionXaAdapter;
import org.rhq.helpers.pluginAnnotations.agent.DataType;
import org.rhq.helpers.pluginAnnotations.agent.DisplayType;
import org.rhq.helpers.pluginAnnotations.agent.MeasurementType;
import org.rhq.helpers.pluginAnnotations.agent.Metric;
import org.rhq.helpers.pluginAnnotations.agent.Operation;
import org.rhq.helpers.pluginAnnotations.agent.Parameter;
import org.infinispan.util.Histogram;

import javax.transaction.RollbackException;
import javax.transaction.Status;
import javax.transaction.SystemException;
import javax.transaction.Transaction;
import javax.transaction.TransactionManager;
import javax.transaction.xa.XAException;
import javax.transaction.xa.Xid;
import java.util.concurrent.atomic.AtomicLong;



/**
 * Interceptor in charge with handling transaction related operations, e.g enlisting cache as an transaction
 * participant, propagating remotely initiated changes.
 *
 * @author <a href="mailto:manik@jboss.org">Manik Surtani (manik@jboss.org)</a>
 * @author Mircea.Markus@jboss.com
 * @see org.infinispan.transaction.xa.TransactionXaAdapter
 * @since 4.0
 */
@MBean(objectName = "Transactions", description = "Component that manages the cache's participation in JTA transactions.")
public class TxInterceptor extends CommandInterceptor {

   private TransactionManager tm;
   private TransactionLog transactionLog;
   private TransactionTable txTable;

   private final AtomicLong prepares = new AtomicLong(0);
   private final AtomicLong commits = new AtomicLong(0);
   private final AtomicLong rollbacks = new AtomicLong(0);
   private final AtomicLong global_wrt_tx_commits = new AtomicLong(0);//SEB  # of commit in the system
   private final AtomicLong wrt_tx_commits = new AtomicLong(0); //SEBDIE     # of commit relevant to LOCAL txs
   private final AtomicLong wrt_tx_got_to_prepare = new AtomicLong(0); //SEB
   private final AtomicLong wrt_tx_started= new AtomicLong(0);//SEB
   private final AtomicLong wrt_tx_local_exec= new AtomicLong(0); //SEBDIE
   private final AtomicLong wrt_sux_tx_local_exec = new AtomicLong(0); //SEBDIE
   private final AtomicLong rd_tx_exec= new AtomicLong(0);//SEBDIE
   private final AtomicLong rd_tx=new AtomicLong(0); //SEBDIE
   private final AtomicLong numPuts= new AtomicLong(0); //SeBDIE
   private final AtomicLong commitTime = new AtomicLong(0); //SEBDIE




   /*
   * Maybe we need a better management of the overflows?
   */
   private final AtomicLong sumAvgTxReplayDuration=new AtomicLong(0);//DIE
   private final AtomicLong sumSuccessfulTxReplayDuration= new AtomicLong(0);
   private final AtomicLong performedReplayedTx=new AtomicLong(0);//DIE



   @ManagedAttribute(description = "Enables or disables the gathering of statistics by this component", writable = true)
   private boolean statisticsEnabled;
   private CommandsFactory commandsFactory;
   private InvocationContextContainer icc;
   private InterceptorChain invoker;


   @Inject
   public void init(TransactionManager tm, TransactionTable txTable, TransactionLog transactionLog, Configuration c, CommandsFactory commandsFactory, InvocationContextContainer icc, InterceptorChain invoker) {
      this.configuration = c;
      this.tm = tm;
      this.transactionLog = transactionLog;
      this.txTable = txTable;
      this.commandsFactory = commandsFactory;
      this.icc = icc;
      this.invoker = invoker;



      setStatisticsEnabled(configuration.isExposeJmxStatistics());
   }

   @Override
   public Object visitPrepareCommand(TxInvocationContext ctx, PrepareCommand command) throws Throwable {
      if (!ctx.isOriginLocal()) {
          //SEB
          ((RemoteTxInvocationContext)ctx).setReplicasPolicyMode(Configuration.ReplicasPolicyMode.PC);
          long time_to_replay=0;
          long timer_replay_begin=System.nanoTime();
         // replay modifications
         for (VisitableCommand modification : command.getModifications()) {
            VisitableCommand toReplay = getCommandToReplay(modification);
            if (toReplay != null) {
               /**
                 * Diego: if I experience a TimeoutException in 2PC, I have to catch it and increment the time I spent
                 * in replaying modifications; I don't increment the number of performed replays, so that the
                 * average replay time increases in case of "deadlocks"
                 */
               try{ //DIE
               invokeNextInterceptor(ctx, toReplay);
               }
               catch(Throwable th){   //DIE
                    time_to_replay=System.nanoTime()-timer_replay_begin;
                    this.updateAvgTxReplayDuration(time_to_replay,false);
                    throw th;

               }
            }
         }
         //DIE
         /*
         If the replay of modifications succeedes, I save in the context the time spent, so that
         at commit time I can increment the number of replayed tx and the time spent for them
          */
         time_to_replay=System.nanoTime()-timer_replay_begin;
         ((RemoteTxInvocationContext)ctx).setReplayTime(time_to_replay);
      }else{
         if(this.statisticsEnabled){
            long time=(((LocalTxInvocationContext)ctx)).getLocalLifeTime();
            if(!(ctx.getModifications()==null || ctx.getModifications().isEmpty())){ //SEB
               this.wrt_tx_got_to_prepare.incrementAndGet(); //SEB
               this.wrt_tx_local_exec.addAndGet(time); //SEBDIE
               this.wrt_sux_tx_local_exec.addAndGet(time);//SEBDIE
            }
            else{
               this.rd_tx_exec.addAndGet(time);//SEBDIE
               this.rd_tx.incrementAndGet();//SEBDIE
            }

        }
      }
      //if it is remote and 2PC then first log the tx only after replying mods
      if (!command.isOnePhaseCommit()) {
         transactionLog.logPrepare(command);
      }
      if (this.statisticsEnabled) prepares.incrementAndGet();
      Object result = invokeNextInterceptor(ctx, command);
      if (command.isOnePhaseCommit()) {
         transactionLog.logOnePhaseCommit(ctx.getGlobalTransaction(), command.getModifications());
      }
      return result;
   }
        //SEBDIE
   @Override
   public Object visitPassiveReplicationCommand(TxInvocationContext ctx, PassiveReplicationCommand command) throws Throwable {
      if (!ctx.isOriginLocal()) {
          if (this.statisticsEnabled) {
            commits.incrementAndGet();
            if(!(ctx.getModifications()==null || ctx.getModifications().isEmpty())){ //SEB

               global_wrt_tx_commits.incrementAndGet(); //SEB
            }

         }
         //SEB
          ((RemoteTxInvocationContext)ctx).setReplicasPolicyMode(Configuration.ReplicasPolicyMode.PASSIVE_REPLICATION);
         // replay modifications
         //DIE
         long time_to_replay=System.nanoTime();
         for (VisitableCommand modification : command.getModifications()) {
            VisitableCommand toReplay = getCommandToReplay(modification);

            if (toReplay != null) {
               invokeNextInterceptor(ctx, toReplay);
            }
          }
          if(this.statisticsEnabled){
              long total_replayed_time=System.nanoTime()-time_to_replay;
              this.updateAvgTxReplayDuration(total_replayed_time,true);
              this.sumSuccessfulTxReplayDuration.addAndGet(total_replayed_time);
            }
      }
      else{

         if(this.statisticsEnabled){
            long time=(((LocalTxInvocationContext)ctx)).getLocalLifeTime();
            if(!(ctx.getModifications()==null || ctx.getModifications().isEmpty())){ //SEB
               this.wrt_tx_got_to_prepare.incrementAndGet(); //SEB
               this.global_wrt_tx_commits.incrementAndGet(); //SEB
               this.wrt_tx_local_exec.addAndGet(time); //SEBDIE
               this.wrt_sux_tx_local_exec.addAndGet(time);//SEBDIE
               this.wrt_tx_commits.incrementAndGet(); //SEBDIE
            }
            else{
               this.rd_tx_exec.addAndGet(time);//SEBDIE
               this.rd_tx.incrementAndGet();//SEBDIE
            }
         }
      }


      Object result = invokeNextInterceptor(ctx, command);



      return result;
   }

   @Override
   public Object visitCommitCommand(TxInvocationContext ctx, CommitCommand command) throws Throwable {
      if (this.statisticsEnabled) {
         commits.incrementAndGet();
         if(!(ctx.getModifications()==null || ctx.getModifications().isEmpty())){ //SEB

            global_wrt_tx_commits.incrementAndGet(); //SEB
            if(!ctx.isOriginLocal()){
               this.updateAvgTxReplayDuration(((RemoteTxInvocationContext)ctx).getReplayTime(),true);//DIE
               this.sumSuccessfulTxReplayDuration.addAndGet(((RemoteTxInvocationContext)ctx).getReplayTime());
            }
            else{
               wrt_tx_commits.incrementAndGet();
            }
         }

      }
      long commitTime=0;
      if(statisticsEnabled && ctx.isInTxScope() && ctx.isOriginLocal()){
           commitTime=System.nanoTime();
      }
      Object result = invokeNextInterceptor(ctx, command);
        if(statisticsEnabled && ctx.isInTxScope() && ctx.isOriginLocal()){
          commitTime=System.nanoTime()-commitTime;
      }

      this.commitTime.addAndGet(commitTime);
      transactionLog.logCommit(command.getGlobalTransaction());

      return result;
   }

   @Override
   public Object visitRollbackCommand(TxInvocationContext ctx, RollbackCommand command) throws Throwable {
      if (this.statisticsEnabled){
         rollbacks.incrementAndGet();
      }
      transactionLog.rollback(command.getGlobalTransaction());
      return invokeNextInterceptor(ctx, command);
   }

   @Override
   public Object visitLockControlCommand(TxInvocationContext ctx, LockControlCommand command) throws Throwable {
       return enlistReadAndInvokeNext(ctx, command);
   }

   @Override
   public Object visitPutKeyValueCommand(InvocationContext ctx, PutKeyValueCommand command) throws Throwable {
      return enlistWriteAndInvokeNext(ctx, command);
   }

   @Override
   public Object visitRemoveCommand(InvocationContext ctx, RemoveCommand command) throws Throwable {
      return enlistWriteAndInvokeNext(ctx, command);
   }

   @Override
   public Object visitReplaceCommand(InvocationContext ctx, ReplaceCommand command) throws Throwable {
      return enlistWriteAndInvokeNext(ctx, command);
   }

   @Override
   public Object visitClearCommand(InvocationContext ctx, ClearCommand command) throws Throwable {
      return enlistWriteAndInvokeNext(ctx, command);
   }

   @Override
   public Object visitPutMapCommand(InvocationContext ctx, PutMapCommand command) throws Throwable {
      return enlistWriteAndInvokeNext(ctx, command);
   }

   @Override
   public Object visitInvalidateCommand(InvocationContext ctx, InvalidateCommand invalidateCommand) throws Throwable {
      return enlistWriteAndInvokeNext(ctx, invalidateCommand);
   }

   @Override
   public Object visitGetKeyValueCommand(InvocationContext ctx, GetKeyValueCommand command) throws Throwable {
      return enlistReadAndInvokeNext(ctx, command);
   }

   private Object enlistReadAndInvokeNext(InvocationContext ctx, VisitableCommand command) throws Throwable {
      if (shouldEnlist(ctx)) {
         LocalTransaction localTransaction = enlist(ctx);
         LocalTxInvocationContext localTxContext = (LocalTxInvocationContext) ctx;
         localTxContext.setLocalTransaction(localTransaction);
      }
      return invokeNextInterceptor(ctx, command);
   }

   private Object enlistWriteAndInvokeNext(InvocationContext ctx, WriteCommand command) throws Throwable {
      LocalTransaction localTransaction = null;
      boolean shouldAddMod = false;
      if (shouldEnlist(ctx)) {
         localTransaction = enlist(ctx);
         LocalTxInvocationContext localTxContext = (LocalTxInvocationContext) ctx;

         if (localModeNotForced(ctx)) shouldAddMod = true;
         localTxContext.setLocalTransaction(localTransaction);

         //SEBDIE
         if(!localTxContext.hasAlreadyWritten() && this.statisticsEnabled){
            localTxContext.setAlreadyWritten();
            this.wrt_tx_started.getAndIncrement();
         }
      }
      Object rv=null;
      //SEBDIE
      try{
         if(statisticsEnabled && ctx.isInTxScope() && ctx.isOriginLocal()){
             this.numPuts.incrementAndGet();
         }

         rv = invokeNextInterceptor(ctx, command);
      }
      catch(Throwable th){
        if (statisticsEnabled && ctx.isInTxScope() && ctx.isOriginLocal() ){
           long time = (((LocalTxInvocationContext) ctx)).getLocalLifeTime();
           //If tx fail we increment the time spent locally for a write transaction without incrementing the counter of succesful write txs
           wrt_tx_local_exec.getAndAdd(time);
           throw th;
        }
      }
      if (!ctx.isInTxScope())
         transactionLog.logNoTxWrite(command);
      if (command.isSuccessful() && shouldAddMod) localTransaction.addModification(command);
      return rv;
   }
   //SEBDIE
   public LocalTransaction enlist(InvocationContext ctx) throws SystemException, RollbackException {
      Transaction transaction = tm.getTransaction();
      if (transaction == null) throw new IllegalStateException("This should only be called in an tx scope");
      int status = transaction.getStatus();
      if (isNotValid(status)) throw new IllegalStateException("Transaction " + transaction +
            " is not in a valid state to be invoking cache operations on.");
      LocalTransaction localTransaction = txTable.getOrCreateLocalTransaction(transaction, ctx);
      if (!localTransaction.isEnlisted()) { //make sure that you only enlist it once
         try {
            transaction.enlistResource(new TransactionXaAdapter(localTransaction, txTable, commandsFactory, configuration, invoker, icc));
             //DIE
             if(statisticsEnabled){
                localTransaction.startTimer();
             }
         } catch (Exception e) {
            Xid xid = localTransaction.getXid();
            if (xid != null && !ctx.getLockedKeys().isEmpty()) {
               log.debug("Attempting a rollback to clear stale resources!");
               try {
                  TransactionXaAdapter.rollbackImpl(xid, commandsFactory, icc, invoker, txTable);
               } catch (XAException xae) {
                  log.debug("Caught exception attempting to clean up " + xid, xae);
               }
            }
            log.error("Failed to enlist TransactionXaAdapter to transaction");
            throw new CacheException(e);
         }
      }
      return localTransaction;
   }

   private boolean isNotValid(int status) {
      return status != Status.STATUS_ACTIVE && status != Status.STATUS_PREPARING;
   }

   private boolean shouldEnlist(InvocationContext ctx) {
      return ctx.isInTxScope() && ctx.isOriginLocal();
   }

   private boolean localModeNotForced(InvocationContext icx) {
      if (icx.hasFlag(Flag.CACHE_MODE_LOCAL)) {
         if (trace) log.debug("LOCAL mode forced on invocation.  Suppressing clustered events.");
         return false;
      }
      return true;
   }

   @ManagedOperation(description = "Resets statistics gathered by this component")
   @Operation(displayName = "Reset Statistics")
   public void resetStatistics() {
      prepares.set(0);
      commits.set(0);
      rollbacks.set(0);
   }

   @Operation(displayName = "Enable/disable statistics")
   public void setStatisticsEnabled(@Parameter(name = "enabled", description = "Whether statistics should be enabled or disabled (true/false)") boolean enabled) {
      this.statisticsEnabled = enabled;
   }

   @Metric(displayName = "Statistics enabled", dataType = DataType.TRAIT)
   public boolean isStatisticsEnabled() {
      return this.statisticsEnabled;
   }

   @ManagedAttribute(description = "Number of transaction prepares performed since last reset")
   @Metric(displayName = "Prepares", measurementType = MeasurementType.TRENDSUP, displayType = DisplayType.SUMMARY)
   public long getPrepares() {
      return prepares.get();
   }

   @ManagedAttribute(description = "Number of transaction commits performed since last reset")
   @Metric(displayName = "Commits", measurementType = MeasurementType.TRENDSUP, displayType = DisplayType.SUMMARY)
   public long getCommits() {
      return commits.get();
   }

   @ManagedAttribute(description = "Number of transaction rollbacks performed since last reset")
   @Metric(displayName = "Rollbacks", measurementType = MeasurementType.TRENDSUP, displayType = DisplayType.SUMMARY)
   public long getRollbacks() {
      return rollbacks.get();
   }
   //SEB
   @ManagedAttribute(description = "Number of write transaction commits performed since last reset")
   @Metric(displayName = "WriteTxCommits", measurementType = MeasurementType.TRENDSUP, displayType = DisplayType.SUMMARY)
   public long getWriteTxCommits() {
      return global_wrt_tx_commits.get();
   }
   //SEB
   @ManagedAttribute(description = "Number of write transaction in prepare phase since last reset")
   @Metric(displayName = "WriteTxInPrepare", measurementType = MeasurementType.TRENDSUP, displayType = DisplayType.SUMMARY)
   public long getWriteTxInPrepare() {
      return this.wrt_tx_got_to_prepare.get();
   }

   //SEB
   @ManagedAttribute(description = "Number of write transaction started since last reset")
   @Metric(displayName = "WriteTxStarted", measurementType = MeasurementType.TRENDSUP, displayType = DisplayType.SUMMARY)
   public long getWriteTxStarted() {
      return this.wrt_tx_started.get();
   }

   //DIE
    @ManagedAttribute(description = "Average time spent to replay the modifications performed in remote, relevant both to successful and aborted transactions")
    @Metric(displayName = "AvgTxReplayDuration")
    public long getAvgTxReplayDuration(){
       if(!statisticsEnabled)
           return -1;
       if(this.sumAvgTxReplayDuration.get()==0 || this.performedReplayedTx.get()==0)
          return 0;
       return (this.sumAvgTxReplayDuration.get()/this.performedReplayedTx.get());
    }
    
    @ManagedAttribute(description = "Average time spent to replay successfully a remote transaction")
    @Metric(displayName = "AvgSuccessfulTxReplayDuration")
    public long getAvgSuccessfulTxReplayDuration(){
       if(this.sumSuccessfulTxReplayDuration.get()==0 || this.performedReplayedTx.get()==0)
          return 0;
       return (this.sumSuccessfulTxReplayDuration.get()/this.performedReplayedTx.get());
    }

    //SEBDIE
   @ManagedOperation(description = "Resets statistics gathered about the duration of a remote transaction")
   @Operation(displayName = "Reset Replay Duration")
    public void  resetReplayDuration(){
       this.sumAvgTxReplayDuration.set(0);
       this.performedReplayedTx.set(0);

    }
   //SEBDIE
   @ManagedOperation(description = "Resets statistics about the duration of the local component of a Tx (before prepare)")
   @Operation(displayName = "Reset Local Transaction Duration Before Prepare")
    public void resetLocalTxDuration(){
        this.wrt_tx_local_exec.set(0);
        this.rd_tx_exec.set(0);
    }
   //SEBDIE
   @ManagedOperation(description = "Resets all statistics relevant to Transactions")
   @Operation(displayName = "Reset Transactions' stats")
   public void resetTxStats(){
       //pre-existent ones
       this.prepares.set(0);
       this.commits.set(0);
       this.rollbacks.set(0);

       //custom ones
       this.sumAvgTxReplayDuration.set(0);
       this.performedReplayedTx.set(0);
       this.wrt_tx_local_exec.set(0);
       this.rd_tx_exec.set(0);
       this.wrt_tx_commits.set(0);
       this.wrt_tx_started.set(0);
       this.wrt_tx_got_to_prepare.set(0);
       this.commitTime.set(0);
       this.numPuts.set(0);
       this.rd_tx.set(0);
       this.wrt_sux_tx_local_exec.set(0);
       this.global_wrt_tx_commits.set(0);
       this.sumAvgTxReplayDuration.set(0);
       this.sumSuccessfulTxReplayDuration.set(0);
       this.performedReplayedTx.set(0);
   }

   //SEBDIE
   @ManagedAttribute(description = "Average duration of the local part of a write transaction")
   @Metric(displayName = "AvgLocalWriteTxExecutionTime")
   public long getAvgLocalWriteTxExecutionTime(){
        if(!statisticsEnabled)
            return -1;
        if(wrt_tx_commits.get()==0)
            return 0;
        return this.wrt_tx_local_exec.get()/ wrt_tx_commits.get();
    }


   //SEBDIE
   @ManagedAttribute(description = "Total duration of the local part of write transactions")
   @Metric(displayName = "TotalLocalWriteTxExecutionTime")
   public long getTotalLocalWriteTxExecutionTime(){

        return this.wrt_tx_local_exec.get();
   }

   //SEBDIE
   @ManagedAttribute(description = "Average duration of a read-only transaction")
   @Metric(displayName = "AvgReadOnlyExecutionTime")
   public long getAvgLocalReadOnlyExecutionTime(){
        if(!statisticsEnabled){
           return -1;
        }
        if(this.rd_tx.get()==0){
           return 0;
        }
        return this.rd_tx_exec.get()/this.rd_tx.get();
    }

    @ManagedAttribute(description = "Average local duration of a successful write transaction")
    @Metric(displayName = "AvgSuxLocal")
    public long getAvgLocalSuxWriteTxExecutionTime(){
       if(wrt_tx_commits.get()==0)
          return 0;
       return
          wrt_sux_tx_local_exec.get()/ wrt_tx_got_to_prepare.get();
    }


    @ManagedAttribute(description = "Num put operations")
    public long getNumPuts(){
        return this.numPuts.get();
    }

    @ManagedOperation(description =" Reset number of performed put operations")
    public void resetNumPuts(){
        this.numPuts.set(0);
    }

    @ManagedAttribute(description = "Time spent to handle a commit command")
    public long getCommitTime(){
        if(this.wrt_tx_commits.get()==0)
            return 0;
        return this.commitTime.get()/wrt_tx_commits.get();
    }

    @ManagedAttribute(description = "Number of write transaction commited system-wide ")
    public long getGlobalWriteCommits(){
        return this.global_wrt_tx_commits.get();
    }



   /**
    * Designed to be overridden.  Returns a VisitableCommand fit for replaying locally, based on the modification passed
    * in.  If a null value is returned, this means that the command should not be replayed.
    *
    * @param modification modification in a prepare call
    * @return a VisitableCommand representing this modification, fit for replaying, or null if the command should not be
    *         replayed.
    */
   protected VisitableCommand getCommandToReplay(VisitableCommand modification) {
      return modification;
   }

   //DIE
    private void  updateAvgTxReplayDuration(long time_to_replay,boolean commit){
       if(commit){
         this.performedReplayedTx.incrementAndGet();
       }
       this.sumAvgTxReplayDuration.addAndGet(time_to_replay);

    }
}
