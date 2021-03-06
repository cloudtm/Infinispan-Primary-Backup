package org.infinispan.client.hotrod.impl;

import org.infinispan.client.hotrod.Flag;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.client.hotrod.ServerStatistics;
import org.infinispan.client.hotrod.Version;
import org.infinispan.client.hotrod.VersionedValue;
import org.infinispan.client.hotrod.exceptions.RemoteCacheManagerNotStartedException;
import org.infinispan.client.hotrod.exceptions.TransportException;
import org.infinispan.client.hotrod.impl.async.NotifyingFutureImpl;
import org.infinispan.client.hotrod.impl.operations.*;
import org.infinispan.marshall.Marshaller;
import org.infinispan.util.concurrent.NotifyingFuture;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * @author Mircea.Markus@jboss.com
 * @since 4.1
 */
public class RemoteCacheImpl<K, V> extends RemoteCacheSupport<K, V> {

   private static final Log log = LogFactory.getLog(RemoteCacheImpl.class);

   private Marshaller marshaller;
   private final String name;
   private final RemoteCacheManager remoteCacheManager;
   private volatile ExecutorService executorService;
   private OperationsFactory operationsFactory;
   private int estimateKeySize;
   private int estimateValueSize;


   public RemoteCacheImpl(RemoteCacheManager rcm, String name) {
      if (log.isTraceEnabled()) {
         log.trace("Creating remote cache: " + name);
      }
      this.name = name;
      this.remoteCacheManager = rcm;
   }

   public void init(Marshaller marshaller, ExecutorService executorService, OperationsFactory operationsFactory, int estimateKeySize, int estimateValueSize) {
      this.marshaller = marshaller;
      this.executorService = executorService;
      this.operationsFactory = operationsFactory;
      this.estimateKeySize = estimateKeySize;
      this.estimateValueSize = estimateValueSize;
   }

   public RemoteCacheManager getRemoteCacheManager() {
      return remoteCacheManager;
   }

   @Override
   public boolean removeWithVersion(K key, long version) {
      assertRemoteCacheManagerIsStarted();
      RemoveIfUnmodifiedOperation op = operationsFactory.newRemoveIfUnmodifiedOperation(obj2bytes(key, true), version);
      VersionedOperationResponse response = (VersionedOperationResponse) op.execute();
      return response.getCode().isUpdated();
   }

   @Override
   public NotifyingFuture<Boolean> removeWithVersionAsync(final K key, final long version) {
      assertRemoteCacheManagerIsStarted();
      final NotifyingFutureImpl<Boolean> result = new NotifyingFutureImpl<Boolean>();
      Future future = executorService.submit(new Callable() {
         @Override
         public Object call() throws Exception {
            boolean removed = removeWithVersion(key, version);
            result.notifyFutureCompletion();
            return removed;
         }
      });
      result.setExecuting(future);
      return result;
   }

   @Override
   public boolean replaceWithVersion(K key, V newValue, long version, int lifespanSeconds, int maxIdleTimeSeconds) {
      assertRemoteCacheManagerIsStarted();
      ReplaceIfUnmodifiedOperation op = operationsFactory.newReplaceIfUnmodifiedOperation(obj2bytes(key, true), obj2bytes(newValue, false), lifespanSeconds, maxIdleTimeSeconds, version);
      VersionedOperationResponse response = (VersionedOperationResponse) op.execute();
      return response.getCode().isUpdated();
   }

   @Override
   public NotifyingFuture<Boolean> replaceWithVersionAsync(final K key, final V newValue, final long version, final int lifespanSeconds, final int maxIdleSeconds) {
      assertRemoteCacheManagerIsStarted();
      final NotifyingFutureImpl<Boolean> result = new NotifyingFutureImpl<Boolean>();
      Future future = executorService.submit(new Callable() {
         @Override
         public Object call() throws Exception {
            boolean removed = replaceWithVersion(key, newValue, version, lifespanSeconds, maxIdleSeconds);
            result.notifyFutureCompletion();
            return removed;
         }
      });
      result.setExecuting(future);
      return result;
   }

   @Override
   public VersionedValue<V> getVersioned(K key) {
      assertRemoteCacheManagerIsStarted();
      GetWithVersionOperation op = operationsFactory.newGetWithVersionOperation(obj2bytes(key, true));
      BinaryVersionedValue value = (BinaryVersionedValue) op.execute();
      return binary2VersionedValue(value);
   }

   @Override
   public void putAll(Map<? extends K, ? extends V> map, long lifespan, TimeUnit lifespanUnit, long maxIdleTime, TimeUnit maxIdleTimeUnit) {
      assertRemoteCacheManagerIsStarted();
      for (Entry<? extends K, ? extends V> entry : map.entrySet()) {
         put(entry.getKey(), entry.getValue(), lifespan, lifespanUnit, maxIdleTime, maxIdleTimeUnit);
      }
   }

   @Override
   public NotifyingFuture<Void> putAllAsync(final Map<? extends K, ? extends V> data, final long lifespan, final TimeUnit lifespanUnit, final long maxIdle, final TimeUnit maxIdleUnit) {
      assertRemoteCacheManagerIsStarted();
      final NotifyingFutureImpl<Void> result = new NotifyingFutureImpl<Void>();
      Future future = executorService.submit(new Callable() {
         @Override
         public Object call() throws Exception {
            putAll(data, lifespan, lifespanUnit, maxIdle, maxIdleUnit);
            result.notifyFutureCompletion();
            return null;
         }
      });
      result.setExecuting(future);
      return result;

   }

   @Override
   public int size() {
      assertRemoteCacheManagerIsStarted();
      StatsOperation op = operationsFactory.newStatsOperation();
      return Integer.parseInt(((Map<String, String>) op.execute()).get(ServerStatistics.CURRENT_NR_OF_ENTRIES));
   }

   @Override
   public boolean isEmpty() {
      return size() == 0;
   }

   @Override
   public ServerStatistics stats() {
      assertRemoteCacheManagerIsStarted();
      StatsOperation op = operationsFactory.newStatsOperation();
      Map<String, String> statsMap = (Map<String, String>) op.execute();
      ServerStatisticsImpl stats = new ServerStatisticsImpl();
      for (Map.Entry<String, String> entry : statsMap.entrySet()) {
         stats.addStats(entry.getKey(), entry.getValue());
      }
      return stats;
   }

   @Override
   public V put(K key, V value, long lifespan, TimeUnit lifespanUnit, long maxIdleTime, TimeUnit maxIdleTimeUnit) {
      assertRemoteCacheManagerIsStarted();
      int lifespanSecs = toSeconds(lifespan, lifespanUnit);
      int maxIdleSecs = toSeconds(maxIdleTime, maxIdleTimeUnit);
      if (log.isTraceEnabled()) {
         log.trace("About to add (K,V): (" + key + ", " + value + ") lifespanSecs:" + lifespanSecs + ", maxIdleSecs:" + maxIdleSecs);
      }
      PutOperation op = operationsFactory.newPutKeyValueOperation(obj2bytes(key, true), obj2bytes(value, false), lifespanSecs, maxIdleSecs);
      byte[] result = (byte[]) op.execute();
      return (V) bytes2obj(result);
   }


   @Override
   public V putIfAbsent(K key, V value, long lifespan, TimeUnit lifespanUnit, long maxIdleTime, TimeUnit maxIdleTimeUnit) {
      assertRemoteCacheManagerIsStarted();
      int lifespanSecs = toSeconds(lifespan, lifespanUnit);
      int maxIdleSecs = toSeconds(maxIdleTime, maxIdleTimeUnit);
      PutIfAbsentOperation op = operationsFactory.newPutIfAbsentOperation(obj2bytes(key, true), obj2bytes(value, false), lifespanSecs, maxIdleSecs);
      byte[] bytes = (byte[]) op.execute();
      return (V) bytes2obj(bytes);
   }

   @Override
   public V replace(K key, V value, long lifespan, TimeUnit lifespanUnit, long maxIdleTime, TimeUnit maxIdleTimeUnit) {
      assertRemoteCacheManagerIsStarted();
      int lifespanSecs = toSeconds(lifespan, lifespanUnit);
      int maxIdleSecs = toSeconds(maxIdleTime, maxIdleTimeUnit);
      ReplaceOperation op = operationsFactory.newReplaceOperation(obj2bytes(key, true), obj2bytes(value, false), lifespanSecs, maxIdleSecs);
      byte[] bytes = (byte[]) op.execute();
      return (V) bytes2obj(bytes);
   }

   @Override
   public NotifyingFuture<V> putAsync(final K key, final V value, final long lifespan, final TimeUnit lifespanUnit, final long maxIdle, final TimeUnit maxIdleUnit) {
      assertRemoteCacheManagerIsStarted();
      final NotifyingFutureImpl<V> result = new NotifyingFutureImpl<V>();
      Future future = executorService.submit(new Callable() {
         @Override
         public Object call() throws Exception {
            V prevValue = put(key, value, lifespan, lifespanUnit, maxIdle, maxIdleUnit);
            result.notifyFutureCompletion();
            return prevValue;
         }
      });
      result.setExecuting(future);
      return result;
   }

   @Override
   public NotifyingFuture<Void> clearAsync() {
      assertRemoteCacheManagerIsStarted();
      final NotifyingFutureImpl<Void> result = new NotifyingFutureImpl<Void>();
      Future future = executorService.submit(new Callable() {
         @Override
         public Object call() throws Exception {
            clear();
            result.notifyFutureCompletion();
            return null;
         }
      });
      result.setExecuting(future);
      return result;
   }

   @Override
   public NotifyingFuture<V> putIfAbsentAsync(final K key,final V value,final long lifespan,final TimeUnit lifespanUnit,final long maxIdle,final TimeUnit maxIdleUnit) {
      assertRemoteCacheManagerIsStarted();
      final NotifyingFutureImpl<V> result = new NotifyingFutureImpl<V>();
      Future future = executorService.submit(new Callable() {
         @Override
         public Object call() throws Exception {
            V prevValue = putIfAbsent(key, value, lifespan, lifespanUnit, maxIdle, maxIdleUnit);
            result.notifyFutureCompletion();
            return prevValue;
         }
      });
      result.setExecuting(future);
      return result;
   }

   @Override
   public NotifyingFuture<V> removeAsync(final Object key) {
      assertRemoteCacheManagerIsStarted();
      final NotifyingFutureImpl<V> result = new NotifyingFutureImpl<V>();
      Future future = executorService.submit(new Callable() {
         @Override
         public Object call() throws Exception {
            V toReturn = remove(key);
            result.notifyFutureCompletion();
            return toReturn;
         }
      });
      result.setExecuting(future);
      return result;      
   }

   @Override
   public NotifyingFuture<V> replaceAsync(final K key,final V value,final long lifespan,final TimeUnit lifespanUnit,final long maxIdle,final TimeUnit maxIdleUnit) {
      assertRemoteCacheManagerIsStarted();
      final NotifyingFutureImpl<V> result = new NotifyingFutureImpl<V>();
      Future future = executorService.submit(new Callable() {
         @Override
         public Object call() throws Exception {
            V v = replace(key, value, lifespan, lifespanUnit, maxIdle, maxIdleUnit);
            result.notifyFutureCompletion();
            return v;
         }
      });
      result.setExecuting(future);
      return result;
   }

   @Override
   public boolean containsKey(Object key) {
      assertRemoteCacheManagerIsStarted();
      ContainsKeyOperation op = operationsFactory.newContainsKeyOperation(obj2bytes(key, true));
      return (Boolean)op.execute();
   }

   @Override
   public V get(Object key) {
      assertRemoteCacheManagerIsStarted();
      byte[] keyBytes = obj2bytes(key, true);
      GetOperation gco = operationsFactory.newGetKeyOperation(keyBytes);
      byte[] bytes = (byte[]) gco.execute();
      V result = (V) bytes2obj(bytes);
      if (log.isTraceEnabled()) {
         log.trace("For key(" + key + ") returning " + result);
      }
      return result;
   }

   @Override
   public Map<K, V> getBulk() {
      return getBulk(0);
   }

   @Override
   public Map<K, V> getBulk(int size) {
      assertRemoteCacheManagerIsStarted();
      BulkGetOperation op = operationsFactory.newBulkGetOperation(size);
      Map<byte[], byte[]> result = (Map) op.execute();
      Map<K,V> toReturn = new HashMap<K,V>();
      for (Map.Entry<byte[], byte[]> entry : result.entrySet()) {
         V value = (V) bytes2obj(entry.getValue());
         K key = (K) bytes2obj(entry.getKey());
         toReturn.put(key, value);
      }
      return Collections.unmodifiableMap(toReturn);
   }

   @Override
   public V remove(Object key) {
      assertRemoteCacheManagerIsStarted();
      RemoveOperation removeOperation = operationsFactory.newRemoveOperation(obj2bytes(key, true));
      byte[] existingValue = (byte[]) removeOperation.execute();
      return (V) bytes2obj(existingValue);
   }

   @Override
   public void clear() {
      assertRemoteCacheManagerIsStarted();
      ClearOperation op = operationsFactory.newClearOperation() ;
      op.execute();
   }

   @Override
   public void start() {
      if (log.isInfoEnabled()) {
         log.info("Start called, nothing to do here(" + getName() + ")");
      }
   }

   @Override
   public void stop() {
      if (log.isInfoEnabled()) {
         log.info("Stop called, nothing to do here(" + getName() + ")");
      }
   }

   @Override
   public String getName() {
      return name;
   }

   @Override
   public String getVersion() {
      return Version.getProtocolVersion();
   }

   @Override
   public RemoteCache<K, V> withFlags(Flag... flags) {
      operationsFactory.setFlags(flags);
      return this;
   }


   private byte[] obj2bytes(Object o, boolean isKey) {
      try {
         return marshaller.objectToByteBuffer(o, isKey ? estimateKeySize : estimateValueSize);
      } catch (IOException ioe) {
         throw new TransportException("Unable to marshall object of type [" + o.getClass().getName() + "]", ioe);
      } catch (InterruptedException ie) {
         Thread.currentThread().interrupt();
         return null;
      }
   }

   private Object bytes2obj(byte[] bytes) {
      if (bytes == null) return null;
      try {
         return marshaller.objectFromByteBuffer(bytes);
      } catch (Exception e) {
         throw new TransportException("Unable to unmarshall byte stream", e);
      }
   }

   private VersionedValue<V> binary2VersionedValue(BinaryVersionedValue value) {
      if (value == null)
         return null;
      V valueObj = (V) bytes2obj(value.getValue());
      return new VersionedValueImpl<V>(value.getVersion(), valueObj);
   }

   private int toSeconds(long duration, TimeUnit timeUnit) {
      return (int) timeUnit.toSeconds(duration);
   }

   private void assertRemoteCacheManagerIsStarted() {
      if (!remoteCacheManager.isStarted()) {
         String message = "Cannot perform operations on a cache associated with an unstarted RemoteCacheManager. Use RemoteCacheManager.start before using the remote cache.";
         if (log.isInfoEnabled()) {
            log.info(message);
         }
         throw new RemoteCacheManagerNotStartedException(message);
      }
   }
}
