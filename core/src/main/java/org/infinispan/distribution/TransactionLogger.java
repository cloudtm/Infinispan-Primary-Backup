package org.infinispan.distribution;

import org.infinispan.commands.tx.CommitCommand;
import org.infinispan.commands.tx.PrepareCommand;
import org.infinispan.commands.tx.RollbackCommand;
import org.infinispan.commands.write.WriteCommand;

import java.util.Collection;
import java.util.List;

/**
 * Typically adding a command, the following pattern would be used:
 * <p/>
 * <code>
 *
 * if (txLogger.logIfNeeded(cmd)) {
 *     // do NOT proceed with executing this command!
 * } else {
 *     // proceed with executing this command as per normal!
 * }
 *
 * </code>
 * <p/>
 * When draining, the following pattern should be used:
 * <p/>
 * <code>
 *
 * List&lt;WriteCommand&gt; c = null;
 * while (txLogger.shouldDrainWithoutLock()) {
 *     c = txLogger.drain();
 *     applyCommands(c);
 * }
 *
 * c = txLogger.drainAndLock();
 * applyCommands(c);
 * applyPendingPrepares(txLogger.getPendingPrepares());
 * txLogger.unlockAndDisable();
 * </code>
 *
 * @author Manik Surtani
 * @since 4.0
 */
public interface TransactionLogger {
   /**
    * Enables transaction logging
    */
   void enable();

   /**
    * Drains the transaction log and returns a list of what has been drained.
    *
    * @return a list of drained commands
    */
   List<WriteCommand> drain();

   /**
    * Similar to {@link #drain()} except that relevant locks are acquired so that no more commands are added to the
    * transaction log during this process, and transaction logging is disabled after draining.
    *
    * @return list of drained commands
    */
   List<WriteCommand> drainAndLock();

   /**
    * Unlocks and disables the transaction logger.  Should <i>only</i> be called after {@link #drainAndLock()}.
    */
   void unlockAndDisable();

   /**
    * If logging is enabled, will log the command and return true.  Otherwise, will just return false.
    *
    * @param command command to log
    * @return true if logged, false otherwise
    */
   boolean logIfNeeded(WriteCommand command);

   /**
    * Logs a PrepareCommand if needed.
    * @param command PrepoareCommand to log
    */
   void logIfNeeded(PrepareCommand command);

   /**
    * Logs a CommitCommand if needed.
    * @param command CommitCommand to log
    */
   void logIfNeeded(CommitCommand command);

   /**
    * Logs a RollbackCommand if needed.
    * @param command RollbackCommand to log
    */
   void logIfNeeded(RollbackCommand command);

   /**
    * If logging is enabled, will log the commands and return true.  Otherwise, will just return false.
    *
    * @param commands commands to log
    * @return true if logged, false otherwise
    */
   boolean logIfNeeded(Collection<WriteCommand> commands);

   /**
    * Checks whether transaction logging is enabled
    * @return true if enabled, false otherwise.
    */
   boolean isEnabled();

   /**
    * Tests whether the drain() method can be called without a lock.  This is usually true if there is a lot of stuff
    * to drain.  After a certain threshold (once there are relatively few entries in the tx log) this will return false
    * after which you should call drainAndLock() to clear the final parts of the log.
    * @return true if drain() should be called, false if drainAndLock() should be called.
    */
   boolean shouldDrainWithoutLock();

   /**
    * Drains pending prepares.  Note that this should *only* be done after calling drainAndLock() to prevent race
    * conditions
    * @return a list of prepares pending commit or rollback
    */
   Collection<PrepareCommand> getPendingPrepares();
}
