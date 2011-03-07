package org.infinispan.util.concurrent.locks.containers;

import org.infinispan.context.InvocationContextContainer;
import org.infinispan.util.concurrent.locks.OwnableReentrantStubbornLock;

import java.util.concurrent.locks.Lock;

/**
 * @author Sebastiano Peluso
 * @since 5.0
 */
//SEB
public class OwnableReentrantStubbornPerEntryLockContainer extends OwnableReentrantPerEntryLockContainer{

    public OwnableReentrantStubbornPerEntryLockContainer(int concurrencyLevel, InvocationContextContainer icc) {
      super(concurrencyLevel,icc);

   }

   protected Lock newLock() {
      return new OwnableReentrantStubbornLock(icc);
   }
}
