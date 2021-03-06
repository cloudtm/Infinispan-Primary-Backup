package org.infinispan.loaders.bucket;

import org.infinispan.container.entries.InternalCacheEntry;
import org.infinispan.loaders.CacheLoaderException;
import org.infinispan.loaders.LockSupportCacheStore;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Base class for CacheStore implementations that combine entries into buckets when storing data.
 * <p/>
 * A hashing algorithm is used to map keys to buckets, and a bucket consists of a collection of key/value pairs.
 * <p/>
 * This approach, while adding an overhead of having to search through the contents of buckets a relevant entry,
 * allows us to use any Serializable object as a key since the bucket is identified by a hash code.  This hash code
 * is often easy to represent in a physical store, such as a file system, database, etc.
 * <p/>
 *
 * @author Mircea.Markus@jboss.com
 * @author Manik Surtani
 * @since 4.0
 */
public abstract class BucketBasedCacheStore extends LockSupportCacheStore {

   /**
    * Loads an entry from a Bucket, locating the relevant Bucket using the key's hash code.
    *
    * @param key        key of the entry to remove.
    * @param lockingKey the hash of the key, as returned by {@link LockSupportCacheStore#getLockFromKey(Object)}. This
    *                   is required in order to avoid hash re-computation.
    */
   protected InternalCacheEntry loadLockSafe(Object key, String lockingKey) throws CacheLoaderException {
      Bucket bucket = loadBucket(lockingKey);
      if (bucket == null) return null;
      InternalCacheEntry se = bucket.getEntry(key);

      if (se != null && se.isExpired()) {
         // We do not actually remove expired items from the store here.  We leave that up to the implementation,
         // since it may be a costly thing (remote connection, row locking on a JDBC store for example) for a
         // supposedly quick load operation.
         return null;
      } else {
         return se;
      }
   }

   /**
    * Stores an entry in an appropriate Bucket, based on the key's hash code.  If the Bucket does not exist in the
    * underlying store, a new one is created.
    *
    * @param entry      the entry to store
    * @param lockingKey the hash of the key, as returned by {@link LockSupportCacheStore#getLockFromKey(Object)}. This
    *                   is required in order to avoid hash re-computation.
    */
   protected void storeLockSafe(InternalCacheEntry entry, String lockingKey) throws CacheLoaderException {
      Bucket bucket = loadBucket(lockingKey);
      if (bucket != null) {
         bucket.addEntry(entry);
         updateBucket(bucket);
      } else {
         bucket = new Bucket();
         bucket.setBucketName(lockingKey);
         bucket.addEntry(entry);
         insertBucket(bucket);
      }
   }

   /**
    * Removes an entry from a Bucket, locating the relevant Bucket using the key's hash code.
    * @param key        key of the entry to remove.
    * @param lockingKey the hash of the key, as returned by {@link LockSupportCacheStore#getLockFromKey(Object)}. This
    *                   is required in order to avoid hash re-computation.
    */
   protected boolean removeLockSafe(Object key, String lockingKey) throws CacheLoaderException {
      Bucket bucket = loadBucket(lockingKey);
      if (bucket == null) {
         return false;
      } else {
         boolean success = bucket.removeEntry(key);
         if (success) updateBucket(bucket);
         return success;
      }
   }

   /**
    * For {@link BucketBasedCacheStore}s the lock should be acquired at bucket level. So we're locking based on the
    * hash code of the key, as all keys having same hash code will be mapped to same bucket.
    */
   protected String getLockFromKey(Object key) {
      return String.valueOf(key.hashCode());
   }

   /**
    * Inserts a new Bucket in the storage system.  If the bucket already exists, this method should simply update the
    * store with the contents of the bucket - i.e., behave the same as {@link #updateBucket(Bucket)}.
    *
    * @param bucket bucket to insert
    * @throws CacheLoaderException in case of problems with the store.
    */
   protected void insertBucket(Bucket bucket) throws CacheLoaderException {
      // the default behavior is to assume that updateBucket() will create a new bucket, so we just forward calls to
      // updateBucket().
      updateBucket(bucket);
   }

   protected static interface BucketHandler {
      /**
       * Handles a bucket that is passed in.
       * @param bucket bucket to handle.  Cannot be null.
       * @return <tt>true</tt> if <i>no more buckets</i> should be passed in (enoiugh buckets have been handled).  <tt>false</tt> otherwise.
       */
      boolean handle(Bucket bucket) throws CacheLoaderException;
   }

   // ah for closures in Java ...
   protected abstract class CollectionGeneratingBucketHandler<T> implements BucketHandler{
      Set<T> generated = new HashSet<T>();
      public abstract boolean consider(Collection<? extends InternalCacheEntry> entries);
      public Set<T> generate() { return generated; }
      public boolean handle(Bucket bucket) throws CacheLoaderException {
         if (bucket != null) {
            if (bucket.removeExpiredEntries()) updateBucket(bucket);
            boolean enoughLooping = consider(bucket.getStoredEntries());
            if (enoughLooping) return true;
         }
         return false;
      }
   }

   protected Set<InternalCacheEntry> loadAllLockSafe() throws CacheLoaderException {
      CollectionGeneratingBucketHandler<InternalCacheEntry> g = new CollectionGeneratingBucketHandler<InternalCacheEntry>() {
         public boolean consider(Collection<? extends InternalCacheEntry> entries) {
            generated.addAll(entries);
            return false;
         }
      };

      loopOverBuckets(g);
      return g.generate();
   }

   protected Set<InternalCacheEntry> loadLockSafe(final int max) throws CacheLoaderException {
      CollectionGeneratingBucketHandler<InternalCacheEntry> g = new CollectionGeneratingBucketHandler<InternalCacheEntry>() {
         public boolean consider(Collection<? extends InternalCacheEntry> entries) {
            for (Iterator<? extends InternalCacheEntry> i = entries.iterator(); i.hasNext() && generated.size() < max;) generated.add(i.next());
            return generated.size() >= max;
         }
      };

      loopOverBuckets(g);
      return g.generate();
   }

   @Override
   protected Set<Object> loadAllKeysLockSafe(final Set<Object> keysToExclude) throws CacheLoaderException {
      CollectionGeneratingBucketHandler<Object> g = new CollectionGeneratingBucketHandler<Object>() {
         public boolean consider(Collection<? extends InternalCacheEntry> entries) {
            for (InternalCacheEntry ice: entries) if (keysToExclude == null || !keysToExclude.contains(ice.getKey())) generated.add(ice.getKey());
            return false;
         }
      };

      loopOverBuckets(g);
      return g.generate();
   }

   /**
    * A mechanism to loop over all buckets in the cache store.  Implementations should, very simply, loop over all
    * available buckets, and for each deserialized bucket, pass it to the handler.
    * <p />
    * The implementation is expected to loop over <i>all</i> available buckets (in any order), until {@link org.infinispan.loaders.bucket.BucketBasedCacheStore.BucketHandler#handle(Bucket)}
    * returns <tt>true</tt> or there are no more buckets available.
    * <p />
    * @param handler
    * @throws CacheLoaderException
    */
   protected abstract void loopOverBuckets(BucketHandler handler) throws CacheLoaderException;

   /**
    * Updates a bucket in the store with the Bucket passed in to the method.  This method assumes that the bucket
    * already exists in the store, however some implementations may choose to simply create a new bucket if the bucket
    * does not exist.
    * <p />
    * The default behavior is that non-existent buckets are created on the fly.  If this is <i>not</i> the case in your
    * implementation, then you would have to override {@link #insertBucket(Bucket)} as well so that it doesn't blindly
    * forward calls to {@link #updateBucket(Bucket)}.
    * <p />
    * @param bucket bucket to update.
    * @throws CacheLoaderException in case of problems with the store.
    */
   protected abstract void updateBucket(Bucket bucket) throws CacheLoaderException;

   /**
    * Loads a Bucket from the store, based on the hash code of the bucket.
    * @param hash String representation of the Bucket's hash
    * @return a Bucket if one exists, null otherwise.
    * @throws CacheLoaderException in case of problems with the store.
    */
   protected abstract Bucket loadBucket(String hash) throws CacheLoaderException;
}
