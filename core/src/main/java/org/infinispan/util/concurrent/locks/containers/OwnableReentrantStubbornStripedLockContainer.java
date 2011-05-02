package org.infinispan.util.concurrent.locks.containers;

import org.infinispan.context.InvocationContextContainer;
import org.infinispan.util.concurrent.locks.OwnableReentrantStubbornLock;

/**
 * @author Sebastiano Peluso
 * @since 5.0
 */
//SEB
public class OwnableReentrantStubbornStripedLockContainer extends OwnableReentrantStripedLockContainer{

   public OwnableReentrantStubbornStripedLockContainer(int concurrencyLevel, InvocationContextContainer icc){
       super(concurrencyLevel, icc);
   }

   protected void initLocks(int numLocks) {
      sharedLocks = new OwnableReentrantStubbornLock[numLocks];
      for (int i = 0; i < numLocks; i++) sharedLocks[i] = new OwnableReentrantStubbornLock(icc);
   }


}
