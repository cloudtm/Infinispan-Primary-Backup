package org.infinispan.query.backend;

import org.hibernate.search.cfg.SearchConfiguration;
import org.hibernate.search.engine.SearchFactoryImplementor;
import org.hibernate.search.impl.SearchFactoryImpl;
import org.infinispan.Cache;
import org.infinispan.CacheException;
import org.infinispan.factories.ComponentRegistry;
import org.infinispan.factories.InterceptorChainFactory;
import org.infinispan.interceptors.LockingInterceptor;
import org.infinispan.interceptors.base.CommandInterceptor;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

import java.lang.reflect.Field;
import java.util.Properties;

/**
 * <p/>
 * This is a TEMPORARY helper class that will be used to add the QueryInterceptor to the chain and provide Classes to
 * Hibernate Search.
 * <p/>
 * This class needs to be instantiated and then have applyProperties() called on it. This class WILL be removed once
 * other hooks come into Infinispan for versions 4.1 etc.
 *
 * @author Navin Surtani
 * @since 4.0
 */


public class QueryHelper {

   public static final String QUERY_ENABLED_PROPERTY = "infinispan.query.enabled";
   public static final String QUERY_INDEX_LOCAL_ONLY_PROPERTY = "infinispan.query.indexLocalOnly";

   private Cache cache;
   private Properties properties;
   private Class[] classes;
   private SearchFactoryImplementor searchFactory;

   Log log = LogFactory.getLog(getClass());

   /**
    * Constructor that will take in 3 params and build the searchFactory for Hibernate Search.
    * <p/>
    * <p/>
    * Once this constructor is called, the user MUST call applyProperties() to set up the interceptors.
    * <p/>
    *
    * @param cache      - the cache instance.
    * @param properties - {@link java.util.Properties}
    * @param classes    - the Class[] for Hibernate Search.
    */

   public QueryHelper(Cache cache, Properties properties, Class... classes) {
      // assume cache is already created and running.
      // otherwise, start the cache!!
      if (cache.getStatus().needToInitializeBeforeStart()) {
         log.debug("Cache not started.  Starting cache first.");
         cache.start();
      }

      if (classes.length == 0) {
         throw new IllegalArgumentException("You haven't passed in any classes to index.");
      }

      // Make the validation check here.
      validateClasses(classes);

      if (properties == null) log.debug("Properties is null.");

      this.cache = cache;
      this.properties = properties;
      this.classes = classes;

      // Set up the search factory for hibernate search first.

      SearchConfiguration cfg = new SearchableCacheConfiguration(classes, properties);
      searchFactory = new SearchFactoryImpl(cfg);

      applyProperties();
   }

   /**
    * This method MUST be called if running the query module and you want to index objects in the cache.
    * <p/>
    * <p/>
    * <p/>
    * e.g.:- QueryHelper.applyQueryProperties(); has to be used BEFORE any objects are put in the cache so that they can
    * be indexed.
    * <p/>
    * <p/>
    * <p/>
    * <p/>
    * Anything put before calling this method will NOT not be picked up by the {@link QueryInterceptor} and hence not be
    * indexed.
    */

   private void applyProperties() {
      if (log.isDebugEnabled()) log.debug("Entered QueryHelper.applyProperties()");

      // If the query property is set to true, i.e. we want to query objects in the cache, then we need to add the QueryInterceptor.
      boolean query = Boolean.getBoolean(QUERY_ENABLED_PROPERTY);

      if (query) {

         boolean indexLocal = Boolean.getBoolean(QUERY_INDEX_LOCAL_ONLY_PROPERTY);

         try {
            if (indexLocal) {
               // Add a LocalQueryInterceptor to the chain
               addInterceptor(LocalQueryInterceptor.class);
            }
            // We're indexing data even if it comes from other sources

            else {
               // Add in a QueryInterceptor to the chain
               addInterceptor(QueryInterceptor.class);
            }
         } catch (Exception e) {
            throw new CacheException("Unable to add interceptor", e);
         }
      }
   }

   /**
    * Simple getter.
    *
    * @return the {@link org.hibernate.search.engine.SearchFactoryImplementor} instance being used.
    */

   public SearchFactoryImplementor getSearchFactory() {
      return searchFactory;
   }

   /**
    * Simple getter.
    *
    * @return the class[].
    */


   public Class[] getClasses() {
      return classes;
   }

   /**
    * Simple getter.
    *
    * @return {@link java.util.Properties}
    */

   public Properties getProperties() {
      return properties;
   }


   // Private method that adds the interceptor from the classname parameter.


   private void addInterceptor(Class<? extends QueryInterceptor> interceptorClass)
         throws IllegalAccessException, InstantiationException {

      // get the component registry and then register the searchFactory.
      ComponentRegistry cr = cache.getAdvancedCache().getComponentRegistry();
      cr.registerComponent(searchFactory, SearchFactoryImplementor.class);

      // Get the interceptor chain factory so I can create my interceptor.
      InterceptorChainFactory icf = cr.getComponent(InterceptorChainFactory.class);

      CommandInterceptor inter = icf.createInterceptor(interceptorClass);
      cr.registerComponent(inter, QueryInterceptor.class);

      cache.getAdvancedCache().addInterceptorAfter(inter, LockingInterceptor.class);

   }

   //This is to check that both the @ProvidedId is present and the the @DocumentId is not present. This is because
   // don't want both of these 2 annotations used at the same time.

   private void validateClasses(Class... classes) {
      for (Class c : classes) {
         if (!c.isAnnotationPresent(org.hibernate.search.annotations.ProvidedId.class)) {
            throw new IllegalArgumentException("There is no provided id on " + c.getName() + " class");
         }

         for (Field field : c.getFields()) {
            if (field.getAnnotation(org.hibernate.search.annotations.DocumentId.class) != null) {
               throw new IllegalArgumentException("Please remove the documentId annotation in " + c.getName());
            }
         }

         for (Field field : c.getDeclaredFields()) {
            if (field.getAnnotation(org.hibernate.search.annotations.DocumentId.class) != null) {
               throw new IllegalArgumentException("Please remove the documentId annotation in " + c.getName());
            }
         }
      }
   }
}