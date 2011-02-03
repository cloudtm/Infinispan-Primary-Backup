package org.infinispan.commands.tx;

import org.infinispan.commands.Visitor;
import org.infinispan.commands.write.WriteCommand;
import org.infinispan.context.InvocationContext;
import org.infinispan.context.impl.TxInvocationContext;
import org.infinispan.transaction.xa.GlobalTransaction;

import java.util.List;

/**
 * @author Sebastiano Peluso
 * @author Diego Didona
 * @since 5.0
 */
//SEBDIE
public class PassiveReplicationCommand extends PrepareCommand{

   public static final byte COMMAND_ID = 18;


   public PassiveReplicationCommand(GlobalTransaction gtx, WriteCommand... modifications) {
      super(gtx, true, modifications);
   }

   public PassiveReplicationCommand(GlobalTransaction gtx, List<WriteCommand> commands) {
      super(gtx, commands, true);
   }

   public PassiveReplicationCommand() {
      super();
   }

   public Object acceptVisitor(InvocationContext ctx, Visitor visitor) throws Throwable {
      return visitor.visitPassiveReplicationCommand((TxInvocationContext) ctx, this);
   }

   public byte getCommandId() {
      return COMMAND_ID;
   }


   public PassiveReplicationCommand copy() {
      PassiveReplicationCommand copy = new PassiveReplicationCommand();
      copy.globalTx = globalTx;
      copy.modifications = modifications == null ? null : modifications.clone();
      copy.onePhaseCommit = onePhaseCommit;
      return copy;
   }

   @Override
   public Object[] getParameters() {
      int numMods = modifications == null ? 0 : modifications.length;
      Object[] retval = new Object[numMods + 4];
      retval[0] = globalTx;
      retval[1] = cacheName;
      retval[2] = onePhaseCommit;
      retval[3] = numMods;
      if (numMods > 0) System.arraycopy(modifications, 0, retval, 4, numMods);
      return retval;
   }

   @Override
   @SuppressWarnings("unchecked")
   public void setParameters(int commandId, Object[] args) {
      globalTx = (GlobalTransaction) args[0];
      cacheName = (String) args[1];
      onePhaseCommit = (Boolean) args[2];
      int numMods = (Integer) args[3];
      if (numMods > 0) {
         modifications = new WriteCommand[numMods];
         System.arraycopy(args, 4, modifications, 0, numMods);
      }
   }
}
