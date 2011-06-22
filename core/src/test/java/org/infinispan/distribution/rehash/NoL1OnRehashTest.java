package org.infinispan.distribution.rehash;

import org.testng.annotations.Test;

/**
 * Control for L1OnRehashTest
 *
 * @author Manik Surtani
 * @since 4.0
 */
@Test(groups = "functional", testName = "distribution.rehash.NoL1OnRehashTest")
public class NoL1OnRehashTest extends L1OnRehashTest {
   public NoL1OnRehashTest() {
      super();
      this.l1OnRehash = false;            
   }
}
