package org.infinispan.client.hotrod.impl;

import org.infinispan.client.hotrod.impl.async.DefaultAsyncExecutorFactory;
import org.infinispan.client.hotrod.impl.transport.tcp.RoundRobinBalancingStrategy;
import org.infinispan.client.hotrod.impl.transport.tcp.TcpTransportFactory;
import org.infinispan.marshall.jboss.GenericJBossMarshaller;
import org.infinispan.util.TypedProperties;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

/**
 * Encapsulate all config properties here
 *
 * @author Manik Surtani
 * @version 4.1
 */
public class ConfigurationProperties {
   public static final String TRANSPORT_FACTORY = "infinispan.client.hotrod.transport_factory";
   public static final String SERVER_LIST = "infinispan.client.hotrod.server_list";
   public static final String MARSHALLER = "infinispan.client.hotrod.marshaller";
   public static final String ASYNC_EXECUTOR_FACTORY = "infinispan.client.hotrod.async_executor_factory";
   public static final String DEFAULT_EXECUTOR_FACTORY_POOL_SIZE = "infinispan.client.hotrod.default_executor_factory.pool_size";
   public static final String TCP_NO_DELAY = "infinispan.client.hotrod.tcp_no_delay";
   public static final String PING_ON_STARTUP = "infinispan.client.hotrod.ping_on_startup";
   public static final String REQUEST_BALANCING_STRATEGY = "infinispan.client.hotrod.request_balancing_strategy";
   public static final String KEY_SIZE_ESTIMATE = "infinispan.client.hotrod.key_size_estimate";
   public static final String VALUE_SIZE_ESTIMATE = "infinispan.client.hotrod.value_size_estimate";
   public static final String FORCE_RETURN_VALUES = "infinispan.client.hotrod.force_return_values";
   public static final String HASH_FUNCTION_PREFIX = "infinispan.client.hotrod.hash_function_impl";
   public static final String DEFAULT_EXECUTOR_FACTORY_QUEUE_SIZE ="infinispan.client.hotrod.default_executor_factory.queue_size";
   
   // defaults

   private static final int DEFAULT_KEY_SIZE = 64;
   private static final int DEFAULT_VALUE_SIZE = 512;
   private static final int DEFAULT_HOTROD_PORT = 11222;

   private final TypedProperties props;


   public ConfigurationProperties() {
      this.props = new TypedProperties();
   }

   public ConfigurationProperties(String serverList) {
      this();
      props.setProperty(SERVER_LIST, serverList);
   }

   public ConfigurationProperties(Properties props) {
      this.props = props == null ? new TypedProperties() : TypedProperties.toTypedProperties(props);
   }

   public String getTransportFactory() {
      return props.getProperty(TRANSPORT_FACTORY, TcpTransportFactory.class.getName());
   }

   public Collection<InetSocketAddress> getServerList() {
      Set<InetSocketAddress> addresses = new HashSet<InetSocketAddress>();
      String servers = props.getProperty(SERVER_LIST, "127.0.0.1:" + DEFAULT_HOTROD_PORT);
      for (String server: servers.split(";")) {
         String[] components = server.trim().split(":");
         String host = components[0];
         int port = DEFAULT_HOTROD_PORT;
         if (components.length > 1) port = Integer.parseInt(components[1]);
         addresses.add(new InetSocketAddress(host, port));
      }

      if (addresses.isEmpty()) throw new IllegalStateException("No Hot Rod servers specified!");
      
      return addresses;
   }

   public String getMarshaller() {
      return props.getProperty(MARSHALLER, GenericJBossMarshaller.class.getName());
   }

   public String getAsyncExecutorFactory() {
      return props.getProperty(ASYNC_EXECUTOR_FACTORY, DefaultAsyncExecutorFactory.class.getName());
   }

   public int getDefaultExecutorFactoryPoolSize() {
      return 99; // TODO
   }

   public boolean getTcpNoDelay() {
      return props.getBooleanProperty(TCP_NO_DELAY, true);
   }

   public boolean getPingOnStartup() {
      return props.getBooleanProperty(PING_ON_STARTUP, true);
   }

   public String getRequestBalancingStrategy() {
      return props.getProperty(REQUEST_BALANCING_STRATEGY, RoundRobinBalancingStrategy.class.getName());
   }

   public int getKeySizeEstimate() {
      return props.getIntProperty(KEY_SIZE_ESTIMATE, DEFAULT_KEY_SIZE);
   }

   public int getValueSizeEstimate() {
      return props.getIntProperty(VALUE_SIZE_ESTIMATE, DEFAULT_VALUE_SIZE);
   }

   public boolean getForceReturnValues() {
      return props.getBooleanProperty(FORCE_RETURN_VALUES, false);
   }

   public Properties getProperties() {
      return props;
   }
}
