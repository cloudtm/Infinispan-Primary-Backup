/*
* JBoss, Home of Professional Open Source
* Copyright 2005, JBoss Inc., and individual contributors as indicated
* by the @authors tag. See the copyright.txt in the distribution for a
* full listing of individual contributors.
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
package org.infinispan.remoting.responses;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.Set;

import org.infinispan.marshall.AbstractExternalizer;
import org.infinispan.marshall.Ids;
import org.infinispan.util.Util;

/**
 * A response with extended information
 *
 * @author Jason T. Greene
 */
public class ExtendedResponse extends ValidResponse {
   private final boolean replayIgnoredRequests;
   private final Response response;
   //SEBDIE
   private long replayTime=0;

   public ExtendedResponse(Response response, boolean replayIgnoredRequests) {
      this.response = response;
      this.replayIgnoredRequests = replayIgnoredRequests;
   }




   public boolean isReplayIgnoredRequests() {
      return replayIgnoredRequests;
   }

   public Response getResponse() {
      return response;
   }

   public boolean isSuccessful() {
      return response.isSuccessful();
   }

   public long getReplayTime(){
      return this.replayTime;
   }

   public void setReplayTime(long rt){
       this.replayTime=rt;
   }


   public static class Externalizer extends AbstractExternalizer<ExtendedResponse> {
      @Override
      public void writeObject(ObjectOutput output, ExtendedResponse er) throws IOException {
         output.writeBoolean(er.replayIgnoredRequests);
         output.writeObject(er.response);
         //SEBDIE
         output.writeLong(er.replayTime);
      }

      @Override
      public ExtendedResponse readObject(ObjectInput input) throws IOException, ClassNotFoundException {
         boolean replayIgnoredRequests = input.readBoolean();
         Response response = (Response) input.readObject();
         //SEBDIE
         long replay = input.readLong();
          ExtendedResponse er=  new ExtendedResponse(response, replayIgnoredRequests);
          er.setReplayTime(replay);
         return  er;
      }

      @Override
      public Integer getId() {
         return Ids.EXTENDED_RESPONSE;
      }

      @Override
      public Set<Class<? extends ExtendedResponse>> getTypeClasses() {
         return Util.<Class<? extends ExtendedResponse>>asSet(ExtendedResponse.class);
      }
   }
}
