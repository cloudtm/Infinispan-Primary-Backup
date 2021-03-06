package org.infinispan.container.entries;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.Set;

import org.infinispan.marshall.AbstractExternalizer;
import org.infinispan.marshall.Ids;
import org.infinispan.util.Util;

/**
 * An immortal cache value, to correspond with {@link org.infinispan.container.entries.ImmortalCacheEntry}
 * 
 * @author Manik Surtani
 * @since 4.0
 */
public class ImmortalCacheValue implements InternalCacheValue, Cloneable {

   Object value;

   ImmortalCacheValue(Object value) {
      this.value = value;
   }

   public InternalCacheEntry toInternalCacheEntry(Object key) {
      return new ImmortalCacheEntry(key, value);
   }

   public final Object setValue(Object value) {
      Object old = this.value;
      this.value = value;
      return old;
   }

   public Object getValue() {
      return value;
   }

   public boolean isExpired() {
      return false;
   }

   public boolean canExpire() {
      return false;
   }

   public long getCreated() {
      return -1;
   }

   public long getLastUsed() {
      return -1;
   }

   public long getLifespan() {
      return -1;
   }

   public long getMaxIdle() {
      return -1;
   }

   @Override
   public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof ImmortalCacheValue)) return false;

      ImmortalCacheValue that = (ImmortalCacheValue) o;

      if (value != null ? !value.equals(that.value) : that.value != null) return false;

      return true;
   }

   @Override
   public int hashCode() {
      return value != null ? value.hashCode() : 0;
   }

   @Override
   public String toString() {
      return "ImmortalCacheValue{" +
            "value=" + value +
            '}';
   }

   @Override
   public ImmortalCacheValue clone() {
      try {
         return (ImmortalCacheValue) super.clone();
      } catch (CloneNotSupportedException e) {
         throw new RuntimeException("Should never happen", e);
      }
   }

   public static class Externalizer extends AbstractExternalizer<ImmortalCacheValue> {
      @Override
      public void writeObject(ObjectOutput output, ImmortalCacheValue icv) throws IOException {
         output.writeObject(icv.value);
      }

      @Override
      public ImmortalCacheValue readObject(ObjectInput input) throws IOException, ClassNotFoundException {
         Object v = input.readObject();
         return new ImmortalCacheValue(v);
      }

      @Override
      public Integer getId() {
         return Ids.IMMORTAL_VALUE;
      }

      @Override
      public Set<Class<? extends ImmortalCacheValue>> getTypeClasses() {
         return Util.<Class<? extends ImmortalCacheValue>>asSet(ImmortalCacheValue.class);
      }
   }
}
