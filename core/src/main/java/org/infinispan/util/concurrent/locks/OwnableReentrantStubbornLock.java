package org.infinispan.util.concurrent.locks;

import net.jcip.annotations.ThreadSafe;
import org.infinispan.context.InvocationContextContainer;

import java.util.concurrent.TimeUnit;

/**
 * @author Sebastiano Peluso
 * @since 5.0
 */
//SEB
@ThreadSafe
public class OwnableReentrantStubbornLock extends OwnableReentrantLock{

   private transient Object stubbornOwner;

   public OwnableReentrantStubbornLock(InvocationContextContainer icc) {

       super(icc);
       this.stubbornOwner=null;
   }

   public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {


      if(time>=0){
         return tryAcquireNanos(1, unit.toNanos(time));
      }
      else{
         stubbornOwner=currentRequestor();   //Current thread makes a reservation for the lock and...
         acquire(1);                         //... it blocks until the lock is free
         return true;
      }


   }

   @Override
   protected final boolean tryAcquire(int acquires) {
      final Object current = currentRequestor();
      int c = getState();
      if (c == 0 && (this.stubbornOwner==null || this.stubbornOwner.equals(current))) { //We give greater priority to the stubbornOwner
         if (compareAndSetState(0, acquires)) {
            owner = current;
            if(owner.equals(stubbornOwner)) stubbornOwner=null;
            return true;
         }
      } else if (current.equals(owner)) {
         setState(c + acquires);
         return true;
      }
      return false;
   }
}
