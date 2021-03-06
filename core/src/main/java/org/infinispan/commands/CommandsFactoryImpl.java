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
package org.infinispan.commands;

import org.infinispan.Cache;
import org.infinispan.commands.control.LockControlCommand;
import org.infinispan.commands.control.RehashControlCommand;
import org.infinispan.commands.control.StateTransferControlCommand;
import static org.infinispan.commands.control.RehashControlCommand.Type.DRAIN_TX_PREPARES;
import static org.infinispan.commands.control.RehashControlCommand.Type.DRAIN_TX;
import org.infinispan.commands.read.EntrySetCommand;
import org.infinispan.commands.read.GetKeyValueCommand;
import org.infinispan.commands.read.KeySetCommand;
import org.infinispan.commands.read.SizeCommand;
import org.infinispan.commands.read.ValuesCommand;
import org.infinispan.commands.remote.ClusteredGetCommand;
import org.infinispan.commands.remote.MultipleRpcCommand;
import org.infinispan.commands.remote.SingleRpcCommand;
import org.infinispan.commands.tx.CommitCommand;
import org.infinispan.commands.tx.PassiveReplicationCommand;
import org.infinispan.commands.tx.PrepareCommand;
import org.infinispan.commands.tx.RollbackCommand;
import org.infinispan.commands.write.ClearCommand;
import org.infinispan.commands.write.EvictCommand;
import org.infinispan.commands.write.InvalidateCommand;
import org.infinispan.commands.write.InvalidateL1Command;
import org.infinispan.commands.write.PutKeyValueCommand;
import org.infinispan.commands.write.PutMapCommand;
import org.infinispan.commands.write.RemoveCommand;
import org.infinispan.commands.write.ReplaceCommand;
import org.infinispan.commands.write.WriteCommand;
import org.infinispan.config.Configuration;
import org.infinispan.container.DataContainer;
import org.infinispan.container.entries.InternalCacheValue;
import org.infinispan.context.InvocationContextContainer;
import org.infinispan.distribution.ch.ConsistentHash;
import org.infinispan.distribution.DistributionManager;
import org.infinispan.factories.annotations.Inject;
import org.infinispan.factories.annotations.Start;
import org.infinispan.interceptors.InterceptorChain;
import org.infinispan.notifications.cachelistener.CacheNotifier;
import org.infinispan.remoting.transport.Address;
import org.infinispan.transaction.xa.DldGlobalTransaction;
import org.infinispan.transaction.xa.GlobalTransaction;
import org.infinispan.transaction.xa.RemoteTransaction;
import org.infinispan.transaction.xa.TransactionTable;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author Mircea.Markus@jboss.com
 * @author Galder Zamarreño
 * @since 4.0
 */
public class CommandsFactoryImpl implements CommandsFactory {

   private static final Log log = LogFactory.getLog(CommandsFactoryImpl.class);
   private static final boolean trace = log.isTraceEnabled();


   private DataContainer dataContainer;
   private CacheNotifier notifier;
   private Cache cache;
   private String cacheName;

   // some stateless commands can be reused so that they aren't constructed again all the time.
   SizeCommand cachedSizeCommand;
   KeySetCommand cachedKeySetCommand;
   ValuesCommand cachedValuesCommand;
   EntrySetCommand cachedEntrySetCommand;
   private InterceptorChain interceptorChain;
   private DistributionManager distributionManager;
   private InvocationContextContainer icc;
   private TransactionTable txTable;
   private Configuration configuration;

   @Inject
   public void setupDependencies(DataContainer container, CacheNotifier notifier, Cache cache,
                                 InterceptorChain interceptorChain, DistributionManager distributionManager,
                                 InvocationContextContainer icc, TransactionTable txTable, Configuration configuration) {
      this.dataContainer = container;
      this.notifier = notifier;
      this.cache = cache;
      this.interceptorChain = interceptorChain;
      this.distributionManager = distributionManager;
      this.icc = icc;
      this.txTable = txTable;
      this.configuration = configuration;
   }

   @Start(priority = 1)
   // needs to happen early on
   public void start() {
      cacheName = cache.getName();
   }

   public PutKeyValueCommand buildPutKeyValueCommand(Object key, Object value, long lifespanMillis, long maxIdleTimeMillis) {
      return new PutKeyValueCommand(key, value, false, notifier, lifespanMillis, maxIdleTimeMillis);
   }

   public RemoveCommand buildRemoveCommand(Object key, Object value) {
      return new RemoveCommand(key, value, notifier);
   }

   public InvalidateCommand buildInvalidateCommand(Object... keys) {
      return new InvalidateCommand(notifier, keys);
   }

   public InvalidateCommand buildInvalidateFromL1Command(boolean forRehash, Object... keys) {
      return new InvalidateL1Command(forRehash, dataContainer, configuration, distributionManager, notifier, keys);
   }

   public InvalidateCommand buildInvalidateFromL1Command(boolean forRehash, Collection<Object> keys) {
      return new InvalidateL1Command(forRehash, dataContainer, configuration, distributionManager, notifier, keys);
   }

   public ReplaceCommand buildReplaceCommand(Object key, Object oldValue, Object newValue, long lifespan, long maxIdleTimeMillis) {
      return new ReplaceCommand(key, oldValue, newValue, lifespan, maxIdleTimeMillis);
   }

   public SizeCommand buildSizeCommand() {
      if (cachedSizeCommand == null) {
         cachedSizeCommand = new SizeCommand(dataContainer);
      }
      return cachedSizeCommand;
   }

   public KeySetCommand buildKeySetCommand() {
      if (cachedKeySetCommand == null) {
         cachedKeySetCommand = new KeySetCommand(dataContainer);
      }
      return cachedKeySetCommand;
   }

   public ValuesCommand buildValuesCommand() {
      if (cachedValuesCommand == null) {
         cachedValuesCommand = new ValuesCommand(dataContainer);
      }
      return cachedValuesCommand;
   }

   public EntrySetCommand buildEntrySetCommand() {
      if (cachedEntrySetCommand == null) {
         cachedEntrySetCommand = new EntrySetCommand(dataContainer);
      }
      return cachedEntrySetCommand;
   }

   public GetKeyValueCommand buildGetKeyValueCommand(Object key) {
      return new GetKeyValueCommand(key, notifier);
   }

   public PutMapCommand buildPutMapCommand(Map map, long lifespan, long maxIdleTimeMillis) {
      return new PutMapCommand(map, notifier, lifespan, maxIdleTimeMillis);
   }

   public ClearCommand buildClearCommand() {
      return new ClearCommand(notifier);
   }

   public EvictCommand buildEvictCommand(Object key) {
      return new EvictCommand(key, notifier);
   }

   public PrepareCommand buildPrepareCommand(GlobalTransaction gtx, List<WriteCommand> modifications, boolean onePhaseCommit) {
      PrepareCommand command = new PrepareCommand(gtx, modifications, onePhaseCommit);
      command.setCacheName(cacheName);
      return command;
   }
     //SEBDIE
   public PassiveReplicationCommand buildPassiveReplicationCommand(GlobalTransaction gtx, List<WriteCommand> modifications) {
      PassiveReplicationCommand command = new PassiveReplicationCommand(gtx, modifications);
      command.setCacheName(cacheName);
      return command;
   }

   public CommitCommand buildCommitCommand(GlobalTransaction gtx) {
      CommitCommand commitCommand = new CommitCommand(gtx);
      commitCommand.setCacheName(cacheName);
      return commitCommand;
   }

   public RollbackCommand buildRollbackCommand(GlobalTransaction gtx) {
      RollbackCommand rollbackCommand = new RollbackCommand(gtx);
      rollbackCommand.setCacheName(cacheName);
      return rollbackCommand;
   }

   public MultipleRpcCommand buildReplicateCommand(List<ReplicableCommand> toReplicate) {
      return new MultipleRpcCommand(toReplicate, cacheName);
   }

   public SingleRpcCommand buildSingleRpcCommand(ReplicableCommand call) {
      return new SingleRpcCommand(cacheName, call);
   }

   public StateTransferControlCommand buildStateTransferControlCommand(boolean block) {
      return new StateTransferControlCommand(block);
   }

   public ClusteredGetCommand buildClusteredGetCommand(Object key) {
      return new ClusteredGetCommand(key, cacheName);
   }

   /**
    * @param isRemote true if the command is deserialized and is executed remote.
    */
   public void initializeReplicableCommand(ReplicableCommand c, boolean isRemote) {
      if (c == null) return;
      switch (c.getCommandId()) {
         case PutKeyValueCommand.COMMAND_ID:
            ((PutKeyValueCommand) c).init(notifier);
            break;
         case PutMapCommand.COMMAND_ID:
            ((PutMapCommand) c).init(notifier);
            break;
         case RemoveCommand.COMMAND_ID:
            ((RemoveCommand) c).init(notifier);
            break;
         case MultipleRpcCommand.COMMAND_ID:
            MultipleRpcCommand rc = (MultipleRpcCommand) c;
            rc.init(interceptorChain, icc);
            if (rc.getCommands() != null)
               for (ReplicableCommand nested : rc.getCommands()) {
                  initializeReplicableCommand(nested, false);
               }
            break;
         case SingleRpcCommand.COMMAND_ID:
            SingleRpcCommand src = (SingleRpcCommand) c;
            src.init(interceptorChain, icc);
            if (src.getCommand() != null)
               initializeReplicableCommand(src.getCommand(), false);

            break;
         case InvalidateCommand.COMMAND_ID:
            InvalidateCommand ic = (InvalidateCommand) c;
            ic.init(notifier);
            break;
         case InvalidateL1Command.COMMAND_ID:
            InvalidateL1Command ilc = (InvalidateL1Command) c;
            ilc.init(configuration, distributionManager, notifier, dataContainer);
            break;
         case PrepareCommand.COMMAND_ID:
            PrepareCommand pc = (PrepareCommand) c;
            pc.init(interceptorChain, icc, txTable);
            pc.initialize(notifier);
            if (pc.getModifications() != null)
               for (ReplicableCommand nested : pc.getModifications())  {
                  initializeReplicableCommand(nested, false);
               }
            pc.markTransactionAsRemote(isRemote);
            if (configuration.isEnableDeadlockDetection() && isRemote) {
               DldGlobalTransaction transaction = (DldGlobalTransaction) pc.getGlobalTransaction();
               transaction.setLocksHeldAtOrigin(pc.getAffectedKeys());
            }
            break;
         case PassiveReplicationCommand.COMMAND_ID:
            PassiveReplicationCommand prc = (PassiveReplicationCommand) c;       //SEBDIE
            prc.init(interceptorChain, icc, txTable);
            prc.initialize(notifier);
            if (prc.getModifications() != null)
               for (ReplicableCommand nested : prc.getModifications())  {
                  initializeReplicableCommand(nested, false);
               }
            prc.markTransactionAsRemote(isRemote);     //Do we have to add DeadlockDetection as in PrepareCOmmand???????
            break;
         case CommitCommand.COMMAND_ID:
            CommitCommand commitCommand = (CommitCommand) c;
            commitCommand.init(interceptorChain, icc, txTable);
            commitCommand.markTransactionAsRemote(isRemote);
            break;
         case RollbackCommand.COMMAND_ID:
            RollbackCommand rollbackCommand = (RollbackCommand) c;
            rollbackCommand.init(interceptorChain, icc, txTable);
            rollbackCommand.markTransactionAsRemote(isRemote);
            break;
         case ClearCommand.COMMAND_ID:
            ClearCommand cc = (ClearCommand) c;
            cc.init(notifier);
            break;
         case ClusteredGetCommand.COMMAND_ID:
            ClusteredGetCommand clusteredGetCommand = (ClusteredGetCommand) c;
            clusteredGetCommand.initialize(dataContainer, icc, this, interceptorChain);
            break;
         case LockControlCommand.COMMAND_ID:
            LockControlCommand lcc = (LockControlCommand) c;
            lcc.init(interceptorChain, icc, txTable);
            lcc.markTransactionAsRemote(isRemote);
            if (configuration.isEnableDeadlockDetection() && isRemote) {
               DldGlobalTransaction gtx = (DldGlobalTransaction) lcc.getGlobalTransaction();
               RemoteTransaction transaction = txTable.getRemoteTransaction(gtx);
               if (transaction != null) {
                  if (!configuration.getCacheMode().isDistributed()) {
                     Set<Object> keys = txTable.getLockedKeysForRemoteTransaction(gtx);
                     GlobalTransaction gtx2 = transaction.getGlobalTransaction();
                     ((DldGlobalTransaction) gtx2).setLocksHeldAtOrigin(keys);
                     gtx.setLocksHeldAtOrigin(keys);
                  } else {
                     GlobalTransaction gtx2 = transaction.getGlobalTransaction();
                     ((DldGlobalTransaction) gtx2).setLocksHeldAtOrigin(gtx.getLocksHeldAtOrigin());
                  }
               }
            }
            break;
         case RehashControlCommand.COMMAND_ID:
            RehashControlCommand rcc = (RehashControlCommand) c;
            rcc.init(distributionManager, configuration, dataContainer, this);
            break;
         default:
            if (trace)
               log.trace("Nothing to initialize for command: " + c);
      }
   }

   public LockControlCommand buildLockControlCommand(Collection keys, boolean implicit) {
      return new LockControlCommand(keys, cacheName, implicit);
   }

   public RehashControlCommand buildRehashControlCommand(RehashControlCommand.Type type, Address sender) {
      return buildRehashControlCommand(type, sender, null, null, null, null);
   }

   public RehashControlCommand buildRehashControlCommandTxLog(Address sender, List<WriteCommand> commands) {
      return new RehashControlCommand(cacheName, DRAIN_TX, sender, commands, null, this);
   }

   public RehashControlCommand buildRehashControlCommandTxLogPendingPrepares(Address sender, List<PrepareCommand> commands) {
      return new RehashControlCommand(cacheName, DRAIN_TX_PREPARES, sender, null, commands, this);
   }  
   
   public RehashControlCommand buildRehashControlCommand(RehashControlCommand.Type type,
            Address sender, Map<Object, InternalCacheValue> state, ConsistentHash oldCH,
            ConsistentHash newCH, List<Address> leavers) {
      return new RehashControlCommand(cacheName, type, sender, state, oldCH, newCH, leavers, this);
   }
}
