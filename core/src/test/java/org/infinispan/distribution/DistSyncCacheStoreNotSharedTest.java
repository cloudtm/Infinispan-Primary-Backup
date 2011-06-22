/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2009, Red Hat Middleware LLC, and individual contributors
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
package org.infinispan.distribution;

import org.infinispan.Cache;
import org.infinispan.commands.write.ClearCommand;
import org.infinispan.commands.write.PutKeyValueCommand;
import org.infinispan.commands.write.RemoveCommand;
import org.infinispan.commands.write.ReplaceCommand;
import org.infinispan.loaders.CacheLoaderManager;
import org.infinispan.loaders.CacheStore;
import org.infinispan.test.TestingUtil;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;
import org.testng.annotations.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * DistSyncSharedTest.
 *
 * @author Galder Zamarreño
 * @since 4.0
 */
@Test(groups = "functional", testName = "distribution.DistSyncCacheStoreNotSharedTest")
public class DistSyncCacheStoreNotSharedTest extends BaseDistCacheStoreTest {
   private static final Log log = LogFactory.getLog(DistSyncCacheStoreNotSharedTest.class);

   public DistSyncCacheStoreNotSharedTest() {
      sync = true;
      tx = false;
      testRetVals = true;
      shared = false;
   }

   public void testPutFromNonOwner() throws Exception {
      String key = "k2", value = "value2";
      for (Cache<Object, String> c : caches) assert c.isEmpty();
      Cache<Object, String> nonOwner = getFirstNonOwner(key);
      CacheStore nonOwnerStore = TestingUtil.extractComponent(nonOwner, CacheLoaderManager.class).getCacheStore();
      assert !nonOwnerStore.containsKey(key);
      Object retval = nonOwner.put(key, value);
      asyncWait(key, PutKeyValueCommand.class, getSecondNonOwner(key));
      assert !nonOwnerStore.containsKey(key);
      if (testRetVals) assert retval == null;
      assertOnAllCachesAndOwnership(key, value);
   }

   public void testPutFromOwner() throws Exception {
      String key = "k3", value = "value3";
      for (Cache<Object, String> c : caches) assert c.isEmpty();
      getOwners(key)[0].put(key, value);
      asyncWait(key, PutKeyValueCommand.class, getNonOwners(key));
      for (Cache<Object, String> c : caches) {
         CacheStore store = TestingUtil.extractComponent(c, CacheLoaderManager.class).getCacheStore();
         if (isOwner(c, key)) {
            assertIsInContainerImmortal(c, key);
            assert store.containsKey(key);
         } else {
            assertIsNotInL1(c, key);
            assert !store.containsKey(key);
         }
      }
   }

   public void testPutAll() throws Exception {
      String k1 = "1", v1 = "one", k2 = "2", v2 = "two", k3 = "3", v3 = "three", k4 = "4", v4 = "four";
      String[] keys = new String[]{k1, k2, k3, k4};
      Map<String, String> data = new HashMap<String, String>();
      data.put(k1, v1);
      data.put(k2, v2);
      data.put(k3, v3);
      data.put(k4, v4);

      c1.putAll(data);

      for (String key : keys) {
         for (Cache<Object, String> c : caches) {
            CacheStore store = TestingUtil.extractComponent(c, CacheLoaderManager.class).getCacheStore();
            if (isOwner(c, key)) {
               assertIsInContainerImmortal(c, key);
               assert store.containsKey(key);
            } else {
               assert !store.containsKey(key);
            }
         }
      }
   }

   public void testRemoveFromNonOwner() throws Exception {
      String key = "k1", value = "value";
      initAndTest();

      for (Cache<Object, String> c : caches) {
         CacheStore store = TestingUtil.extractComponent(c, CacheLoaderManager.class).getCacheStore();
         if (isOwner(c, key)) {
            assertIsInContainerImmortal(c, key);
            assert store.load(key).getValue().equals(value);
         } else {
            assert !store.containsKey(key);
         }
      }

      Object retval = getFirstNonOwner(key).remove(key);
      asyncWait("k1", RemoveCommand.class, getSecondNonOwner("k1"));
      if (testRetVals) assert "value".equals(retval);
      for (Cache<Object, String> c : caches) {
         CacheStore store = TestingUtil.extractComponent(c, CacheLoaderManager.class).getCacheStore();
         assert !store.containsKey(key);
      }
   }

   public void testReplaceFromNonOwner() throws Exception {
      String key = "k1", value = "value", value2 = "v2";
      initAndTest();

      for (Cache<Object, String> c : caches) {
         CacheStore store = TestingUtil.extractComponent(c, CacheLoaderManager.class).getCacheStore();
         if (isOwner(c, key)) {
            assertIsInContainerImmortal(c, key);
            assert store.load(key).getValue().equals(value);
         } else {
            assert !store.containsKey(key);
         }
      }

      Object retval = getFirstNonOwner(key).replace(key, value2);
      asyncWait(key, ReplaceCommand.class, getSecondNonOwner(key));
      if (testRetVals) assert value.equals(retval);
      for (Cache<Object, String> c : caches) {
         CacheStore store = TestingUtil.extractComponent(c, CacheLoaderManager.class).getCacheStore();
         if (isOwner(c, key)) {
            assertIsInContainerImmortal(c, key);
            assert store.load(key).getValue().equals(value2);
         } else {
            assert !store.containsKey(key);
         }
      }
   }

   public void testClear() throws Exception {
      for (Cache<Object, String> c : caches) assert c.isEmpty();
      for (int i = 0; i < 5; i++) {
         getOwners("k" + i)[0].put("k" + i, "value" + i);
         asyncWait("k" + i, PutKeyValueCommand.class, getNonOwners("k" + i));
      }
      // this will fill up L1 as well
      for (int i = 0; i < 5; i++) assertOnAllCachesAndOwnership("k" + i, "value" + i);
      for (Cache<Object, String> c : caches) assert !c.isEmpty();
      c1.clear();
      asyncWait(null, ClearCommand.class);
      for (Cache<Object, String> c : caches) assert c.isEmpty();
      for (int i = 0; i < 5; i++) {
         String key = "k" + i;
         for (Cache<Object, String> c : caches) {
            CacheStore store = TestingUtil.extractComponent(c, CacheLoaderManager.class).getCacheStore();
            assert !store.containsKey(key);
         }
      }
   }
}
