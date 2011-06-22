/*
 *
 * JBoss, the OpenSource J2EE webOS
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */

package org.infinispan.replication;

import org.infinispan.Cache;
import org.infinispan.commands.write.PutKeyValueCommand;
import org.infinispan.config.Configuration;
import org.infinispan.test.MultipleCacheManagersTest;
import org.infinispan.test.TestingUtil;
import static org.testng.AssertJUnit.assertEquals;
import org.testng.annotations.Test;

import javax.transaction.TransactionManager;

@Test(groups = "functional", testName = "replication.AsyncReplTest")
public class AsyncReplTest extends MultipleCacheManagersTest {


   protected void createCacheManagers() throws Throwable {
      Configuration asyncConfiguration = getDefaultClusteredConfig(Configuration.CacheMode.REPL_ASYNC, true);
      createClusteredCaches(2, "asyncRepl", asyncConfiguration);   
   }

   public void testWithNoTx() throws Exception {

      Cache cache1 = cache(0,"asyncRepl");
      Cache cache2 = cache(1,"asyncRepl");
      String key = "key";

      replListener(cache2).expect(PutKeyValueCommand.class);
      cache1.put(key, "value1");
      // allow for replication
      replListener(cache2).waitForRpc();
      assertEquals("value1", cache1.get(key));
      assertEquals("value1", cache2.get(key));

      replListener(cache2).expect(PutKeyValueCommand.class);
      cache1.put(key, "value2");
      assertEquals("value2", cache1.get(key));

      replListener(cache2).waitForRpc();

      assertEquals("value2", cache1.get(key));
      assertEquals("value2", cache2.get(key));
   }

   public void testWithTx() throws Exception {
      Cache cache1 = cache(0,"asyncRepl");
      Cache cache2 = cache(1,"asyncRepl");
      
      String key = "key";
      replListener(cache2).expect(PutKeyValueCommand.class);
      cache1.put(key, "value1");
      // allow for replication
      replListener(cache2).waitForRpc();
      assertNotLocked(cache1, key);

      assertEquals("value1", cache1.get(key));
      assertEquals("value1", cache2.get(key));

      TransactionManager mgr = TestingUtil.getTransactionManager(cache1);
      mgr.begin();
      replListener(cache2).expectWithTx(PutKeyValueCommand.class);
      cache1.put(key, "value2");
      assertEquals("value2", cache1.get(key));
      assertEquals("value1", cache2.get(key));
      mgr.commit();
      replListener(cache2).waitForRpc();
      assertNotLocked(cache1, key);

      assertEquals("value2", cache1.get(key));
      assertEquals("value2", cache2.get(key));

      mgr.begin();
      cache1.put(key, "value3");
      assertEquals("value3", cache1.get(key));
      assertEquals("value2", cache2.get(key));

      mgr.rollback();

      assertEquals("value2", cache1.get(key));
      assertEquals("value2", cache2.get(key));

      assertNotLocked(cache1, key);

   }

   public void simpleTest() throws Exception {
      Cache cache1 = cache(0,"asyncRepl");
      Cache cache2 = cache(1,"asyncRepl");
      
      String key = "key";
      TransactionManager mgr = TestingUtil.getTransactionManager(cache1);

      mgr.begin();
      cache1.put(key, "value3");
      mgr.rollback();

      assertNotLocked(cache1, key);

   }
}
