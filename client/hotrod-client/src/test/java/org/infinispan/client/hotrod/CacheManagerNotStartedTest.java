package org.infinispan.client.hotrod;

import org.infinispan.client.hotrod.exceptions.RemoteCacheManagerNotStartedException;
import org.infinispan.config.Configuration;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.server.hotrod.HotRodServer;
import org.infinispan.test.SingleCacheManagerTest;
import org.infinispan.test.fwk.TestCacheManagerFactory;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterTest;
import org.testng.annotations.Test;

import java.util.HashMap;

/**
 * @author Mircea.Markus@jboss.com
 * @since 4.1
 */
@Test (testName = "client.hotrod.CacheManagerNotStartedTest", groups = "functional")
public class CacheManagerNotStartedTest extends SingleCacheManagerTest {

   private static final String CACHE_NAME = "someName";

   EmbeddedCacheManager cacheManager = null;
   HotRodServer hotrodServer = null;
   RemoteCacheManager remoteCacheManager;

   @Override
   protected EmbeddedCacheManager createCacheManager() throws Exception {
      cacheManager = TestCacheManagerFactory.createLocalCacheManager();
      cacheManager.defineConfiguration(CACHE_NAME, new Configuration());
      hotrodServer = TestHelper.startHotRodServer(cacheManager);
      remoteCacheManager = new RemoteCacheManager("localhost:" + hotrodServer.getHost(), false);
      return cacheManager;
   }

   @AfterTest(alwaysRun = true)
   public void release() {
      if (cacheManager != null) cacheManager.stop();
      if (hotrodServer != null) hotrodServer.stop();
   }

   @AfterClass
   @Override
   protected void destroyAfterClass() {
      super.destroyAfterClass();
      try {
         remoteCacheManager.stop();
      } catch (Exception e) {
         e.printStackTrace();
      }
   }

   public void testGetCacheOperations() {
      assert remoteCacheManager.getCache() != null;
      assert remoteCacheManager.getCache(CACHE_NAME) != null;
      assert !remoteCacheManager.isStarted();
   }

   @Test(expectedExceptions = RemoteCacheManagerNotStartedException.class)
   public void testGetCacheOperations2() {
      remoteCacheManager.getCache().put("k", "v");
   }

   @Test(expectedExceptions = RemoteCacheManagerNotStartedException.class)
   public void testGetCacheOperations3() {
      remoteCacheManager.getCache(CACHE_NAME).put("k", "v");
   }

   @Test(expectedExceptions = RemoteCacheManagerNotStartedException.class)
   public void testPut() {
      cache().put("k", "v");
   }

   @Test(expectedExceptions = RemoteCacheManagerNotStartedException.class)
   public void testPutAsync() {
      cache().putAsync("k", "v");
   }

   @Test(expectedExceptions = RemoteCacheManagerNotStartedException.class)
   public void testGet() {
      cache().get("k");
   }

   @Test(expectedExceptions = RemoteCacheManagerNotStartedException.class)
   public void testReplace() {
      cache().replace("k", "v");
   }

   @Test(expectedExceptions = RemoteCacheManagerNotStartedException.class)
   public void testReplaceAsync() {
      cache().replaceAsync("k", "v");
   }

   @Test(expectedExceptions = RemoteCacheManagerNotStartedException.class)
   public void testPutAll() {
      cache().putAll(new HashMap<Object, Object>());
   }

   @Test(expectedExceptions = RemoteCacheManagerNotStartedException.class)
   public void testPutAllAsync() {
      cache().putAllAsync(new HashMap<Object, Object>());
   }

   @Test(expectedExceptions = RemoteCacheManagerNotStartedException.class)
   public void testVersionedGet() {
      cache().getVersioned("key");
   }

   @Test(expectedExceptions = RemoteCacheManagerNotStartedException.class)
   public void testVersionedRemove() {
      cache().removeWithVersion("key", 12312321l);
   }

   @Test(expectedExceptions = RemoteCacheManagerNotStartedException.class)
   public void testVersionedRemoveAsync() {
      cache().removeWithVersionAsync("key", 12312321l);
   }

   private RemoteCache<Object, Object> cache() {
      return remoteCacheManager.getCache();
   }
}
