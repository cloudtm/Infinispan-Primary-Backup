package org.infinispan.loaders.decorators;

import org.infinispan.CacheException;
import org.infinispan.container.entries.InternalEntryFactory;
import org.infinispan.loaders.CacheLoaderException;
import org.infinispan.loaders.dummy.DummyInMemoryCacheStore;
import org.infinispan.test.TestingUtil;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.AfterTest;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;

import java.util.concurrent.ExecutorService;

@Test(groups = "unit", testName = "loaders.decorators.AsyncTest")
public class AsyncTest {

   AsyncStore store;
   ExecutorService asyncExecutor;


   @BeforeTest
   public void setUp() throws CacheLoaderException {
      store = new AsyncStore(new DummyInMemoryCacheStore(), new AsyncStoreConfig());
      DummyInMemoryCacheStore.Cfg cfg = new DummyInMemoryCacheStore.Cfg();
      cfg.setStore(AsyncTest.class.getName());
      store.init(cfg, null, null);
      store.start();
      asyncExecutor = (ExecutorService) TestingUtil.extractField(store, "executor");
   }

   @AfterTest
   public void tearDown() throws CacheLoaderException {
      if (store != null) store.stop();
   }

   @AfterMethod
   public void clearStore() {
      if (store != null) store.clear();
   }

   public void testRestrictionOnAddingToAsyncQueue() throws Exception {
      store.remove("blah");

      for (int i = 0; i < 4; i++) store.store(InternalEntryFactory.create("k" + i, "v" + i));

      // stop the cache store
      store.stop();
      try {
         store.remove("blah");
         assert false : "Should have restricted this entry from being made";
      }
      catch (CacheException expected) {
      }

      // clean up
      store.start();
   }
}