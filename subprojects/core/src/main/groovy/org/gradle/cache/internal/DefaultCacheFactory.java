/*
 * Copyright 2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.cache.internal;

import org.gradle.CacheUsage;
import org.gradle.api.Action;
import org.gradle.cache.CacheOpenException;
import org.gradle.cache.CacheValidator;
import org.gradle.cache.PersistentCache;
import org.gradle.cache.PersistentIndexedCache;
import org.gradle.cache.internal.filelock.LockOptions;
import org.gradle.internal.Factory;
import org.gradle.internal.concurrent.CompositeStoppable;
import org.gradle.util.GFileUtils;

import java.io.File;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static org.gradle.cache.internal.FileLockManager.LockMode;

public class DefaultCacheFactory implements Factory<CacheFactory> {
    private final Map<File, DirCacheReference> dirCaches = new HashMap<File, DirCacheReference>();
    private final FileLockManager lockManager;
    private final Lock lock = new ReentrantLock();

    public DefaultCacheFactory(FileLockManager fileLockManager) {
        this.lockManager = fileLockManager;
    }

    public CacheFactory create() {
        return new LockingCacheFactory(new CacheFactoryImpl());
    }

    void onOpen(Object cache) {
    }

    void onClose(Object cache) {
    }

    public void close() {
        lock.lock();
        try {
            for (DirCacheReference dirCacheReference : dirCaches.values()) {
                dirCacheReference.close();
            }
        } finally {
            dirCaches.clear();
            lock.unlock();
        }
    }

    private class LockingCacheFactory implements CacheFactory {
        private final CacheFactoryImpl delegate;

        private LockingCacheFactory(CacheFactoryImpl delegate) {
            this.delegate = delegate;
        }

        public PersistentCache open(File cacheDir, String displayName, CacheUsage usage, CacheValidator cacheValidator, Map<String, ?> properties, LockOptions lockOptions, Action<? super PersistentCache> initializer) throws CacheOpenException {
            lock.lock();
            try {
                return delegate.open(cacheDir, displayName, usage, cacheValidator, properties, lockOptions, initializer);
            } finally {
                lock.unlock();
            }
        }

        public PersistentCache openStore(File storeDir, String displayName, LockOptions lockOptions, Action<? super PersistentCache> initializer) throws CacheOpenException {
            lock.lock();
            try {
                return delegate.openStore(storeDir, displayName, lockOptions, initializer);
            } finally {
                lock.unlock();
            }
        }

        public void close() {
            lock.lock();
            try {
                delegate.close();
            } finally {
                lock.unlock();
            }
        }
    }

    private class CacheFactoryImpl implements CacheFactory {
        private final Set<ReferenceTrackingCache> caches = new LinkedHashSet<ReferenceTrackingCache>();

        public PersistentCache open(File cacheDir, String displayName, CacheUsage usage, CacheValidator validator, Map<String, ?> properties, LockOptions lockOptions, Action<? super PersistentCache> action) {
            File canonicalDir = GFileUtils.canonicalise(cacheDir);
            DirCacheReference dirCacheReference = dirCaches.get(canonicalDir);
            if (dirCacheReference == null) {
                if (lockOptions.getMode().equals(LockMode.None)) {
                    // Create nested cache with LockMode#Exclusive (tb discussed) that is opened and closed on Demand in the DelegateOnDemandPersistentDirectoryCache.
                    DefaultPersistentDirectoryCache nestedCache = new DefaultPersistentDirectoryCache(canonicalDir, displayName, usage, validator, properties, lockOptions.withMode(LockMode.Exclusive), action, lockManager);
                    DelegateOnDemandPersistentDirectoryCache onDemandDache = new DelegateOnDemandPersistentDirectoryCache(nestedCache);
                    onDemandDache.open();
                    dirCacheReference = new DirCacheReference(onDemandDache, properties, lockOptions);
                    dirCaches.put(canonicalDir, dirCacheReference);
                } else {
                    ReferencablePersistentCache cache = new DefaultPersistentDirectoryCache(canonicalDir, displayName, usage, validator, properties, lockOptions, action, lockManager);
                    cache.open();
                    dirCacheReference = new DirCacheReference(cache, properties, lockOptions);
                    dirCaches.put(canonicalDir, dirCacheReference);
                }
            } else {
                if (usage == CacheUsage.REBUILD && dirCacheReference.rebuiltBy != this) {
                    throw new IllegalStateException(String.format("Cannot rebuild cache '%s' as it is already open.", cacheDir));
                }
                if (!lockOptions.equals(dirCacheReference.lockOptions)) {
                    throw new IllegalStateException(String.format("Cache '%s' is already open with different options.", cacheDir));
                }
                if (!properties.equals(dirCacheReference.properties)) {
                    throw new IllegalStateException(String.format("Cache '%s' is already open with different state.", cacheDir));
                }
            }
            if (usage == CacheUsage.REBUILD) {
                dirCacheReference.rebuiltBy = this;
            }
            ReferenceTrackingCache wrapper = new ReferenceTrackingCache(dirCacheReference);
            caches.add(wrapper);
            return wrapper;
        }

        public PersistentCache openStore(File storeDir, String displayName, LockOptions lockOptions, Action<? super PersistentCache> initializer) throws CacheOpenException {
            if (initializer != null) {
                throw new UnsupportedOperationException("Initializer actions are not currently supported by the directory store implementation.");
            }
            File canonicalDir = GFileUtils.canonicalise(storeDir);
            DirCacheReference dirCacheReference = dirCaches.get(canonicalDir);
            if (dirCacheReference == null) {
                ReferencablePersistentCache cache = new DefaultPersistentDirectoryStore(canonicalDir, displayName, lockOptions, lockManager);
                cache.open();
                dirCacheReference = new DirCacheReference(cache, Collections.<String, Object>emptyMap(), lockOptions);
                dirCaches.put(canonicalDir, dirCacheReference);
            }
            ReferenceTrackingCache wrapper = new ReferenceTrackingCache(dirCacheReference);
            caches.add(wrapper);
            return wrapper;
        }

        public void close() {
            try {
                CompositeStoppable.stoppable(caches).stop();
            } finally {
                caches.clear();
            }
        }
    }

    private class DirCacheReference {
        private final Map<String, ?> properties;
        private final LockOptions lockOptions;
        private final ReferencablePersistentCache cache;
        private final Set<ReferenceTrackingCache> references = new HashSet<ReferenceTrackingCache>();
        CacheFactoryImpl rebuiltBy;

        public DirCacheReference(ReferencablePersistentCache cache, Map<String, ?> properties, LockOptions lockOptions) {
            this.cache = cache;
            this.properties = properties;
            this.lockOptions = lockOptions;
            onOpen(cache);
        }

        public void addReference(ReferenceTrackingCache cache) {
            references.add(cache);
        }

        public void release(ReferenceTrackingCache cache) {
            lock.lock();
            try {
                if (references.remove(cache) && references.isEmpty()) {
                    close();
                }
            } finally {
                lock.unlock();
            }
        }

        public void close() {
            onClose(cache);
            dirCaches.values().remove(this);
            references.clear();
            cache.close();
        }
    }

    private static class ReferenceTrackingCache implements PersistentCache {
        private final DirCacheReference reference;

        private ReferenceTrackingCache(DirCacheReference reference) {
            this.reference = reference;
            reference.addReference(this);
        }

        @Override
        public String toString() {
            return reference.cache.toString();
        }

        public void close() {
            reference.release(this);
        }

        public File getBaseDir() {
            return reference.cache.getBaseDir();
        }

        public <K, V> PersistentIndexedCache<K, V> createCache(PersistentIndexedCacheParameters<K, V> parameters) {
            return reference.cache.createCache(parameters);
        }

        public <T> T longRunningOperation(String operationDisplayName, Factory<? extends T> action) {
            return reference.cache.longRunningOperation(operationDisplayName, action);
        }

        public void longRunningOperation(String operationDisplayName, Runnable action) {
            reference.cache.longRunningOperation(operationDisplayName, action);
        }

        public <T> T useCache(String operationDisplayName, Factory<? extends T> action) {
            return reference.cache.useCache(operationDisplayName, action);
        }

        public void useCache(String operationDisplayName, Runnable action) {
            reference.cache.useCache(operationDisplayName, action);
        }
    }
}
