package org.infinispan.util;

import java.util.Map;

/**
 * An extension of Map that returns ReversibleOrderedSets
 *
 * @author Manik Surtani
 * @since 4.0
 */
public interface BidirectionalMap<K, V> extends Map<K, V> {

   ReversibleOrderedSet<K> keySet();

   ReversibleOrderedSet<Map.Entry<K, V>> entrySet();
}
