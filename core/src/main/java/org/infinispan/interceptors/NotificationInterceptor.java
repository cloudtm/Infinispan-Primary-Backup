/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2000 - 2008, Red Hat Middleware LLC, and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
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
package org.infinispan.interceptors;

import org.infinispan.commands.tx.CommitCommand;
import org.infinispan.commands.tx.PassiveReplicationCommand;
import org.infinispan.commands.tx.PrepareCommand;
import org.infinispan.commands.tx.RollbackCommand;
import org.infinispan.context.impl.TxInvocationContext;
import org.infinispan.factories.annotations.Inject;
import org.infinispan.notifications.cachelistener.CacheNotifier;
import org.infinispan.interceptors.base.CommandInterceptor;

/**
 * The interceptor in charge of firing off notifications to cache listeners
 *
 * @author <a href="mailto:manik@jboss.org">Manik Surtani</a>
 * @since 4.0
 */
public class NotificationInterceptor extends CommandInterceptor {
   private CacheNotifier notifier;

   @Inject
   public void injectDependencies(CacheNotifier notifier) {
      this.notifier = notifier;
   }

   @Override
   public Object visitPrepareCommand(TxInvocationContext ctx, PrepareCommand command) throws Throwable {
      Object retval = invokeNextInterceptor(ctx, command);
      if (command.isOnePhaseCommit()) notifier.notifyTransactionCompleted(ctx.getGlobalTransaction(), true, ctx);
      return retval;
   }
       //SEBDIE
   @Override
   public Object visitPassiveReplicationCommand(TxInvocationContext ctx, PassiveReplicationCommand command) throws Throwable {
      Object retval = invokeNextInterceptor(ctx, command);
      notifier.notifyTransactionCompleted(ctx.getGlobalTransaction(), true, ctx);
      return retval;
   }

   @Override
   public Object visitCommitCommand(TxInvocationContext ctx, CommitCommand command) throws Throwable {
      Object retval = invokeNextInterceptor(ctx, command);
      notifier.notifyTransactionCompleted(ctx.getGlobalTransaction(), true, ctx);
      return retval;
   }

   @Override
   public Object visitRollbackCommand(TxInvocationContext ctx, RollbackCommand command) throws Throwable {
      Object retval = invokeNextInterceptor(ctx, command);
      notifier.notifyTransactionCompleted(ctx.getGlobalTransaction(), false, ctx);
      return retval;
   }
}
