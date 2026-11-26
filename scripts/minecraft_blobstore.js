// minecraft-web by chiikabu (https://github.com/chiikabu)

(function (root, factory) {
  var api = factory();
  if (typeof module !== "undefined" && module.exports) { module.exports = api; }
  if (typeof root !== "undefined") {
    root.__minecraftHydrateAssets = api.hydrateAssets;
    root.__minecraftInstallBlobStore = function () {
      if (!root.__minecraftBlobStore && typeof root.indexedDB !== "undefined" && root.indexedDB) {
        try { root.__minecraftBlobStore = api.idbBackend(root.indexedDB, "minecraft-assets", "blobs"); }
        catch (e) { root.__minecraftBlobStore = null; }
      }
      return root.__minecraftBlobStore || null;
    };
    root.__minecraftHydrateThen = function (key, continuation) {
      api.hydrateThen(root, key, continuation);
    };
    root.__minecraftInstallIpfsStore = function () {
      if (!root.__minecraftIpfsStore && typeof root.indexedDB !== "undefined" && root.indexedDB) {
        try { root.__minecraftIpfsStore = api.idbBackendMulti(root.indexedDB, "minecraft-ipfs", "blobs"); }
        catch (e) { root.__minecraftIpfsStore = null; }
      }
      return root.__minecraftIpfsStore || null;
    };
    root.__minecraftInstallWebStore = function () {
      if (!root.__minecraftWebStore && typeof root.indexedDB !== "undefined" && root.indexedDB) {
        try { root.__minecraftWebStore = api.idbBackendMulti(root.indexedDB, "minecraft-web", "blobs"); }
        catch (e) { root.__minecraftWebStore = null; }
      }
      return root.__minecraftWebStore || null;
    };
    root.__minecraftIdbBackendMulti = api.idbBackendMulti;
    root.__minecraftInstallOfflineCache = function () {
      if (!root.__minecraftOfflineCache && typeof root.indexedDB !== "undefined" && root.indexedDB) {
        try { root.__minecraftOfflineCache = api.idbBackendMulti(root.indexedDB, "minecraft-offline-cache", "blobs"); }
        catch (e) { root.__minecraftOfflineCache = null; }
      }
      return root.__minecraftOfflineCache || null;
    };
    root.__minecraftFetchThenCache = api.fetchThenCache;
    root.__minecraftPruneStore = api.prune;
  }
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  function req(r) {
    return new Promise(function (resolve, reject) {
      r.onsuccess = function () { resolve(r.result); };
      r.onerror = function () { reject(r.error); };
    });
  }

  function open(idb, dbName, storeName) {
    return new Promise(function (resolve, reject) {
      var o = idb.open(dbName, 1);
      o.onupgradeneeded = function () {
        var db = o.result;
        if (!db.objectStoreNames.contains(storeName)) { db.createObjectStore(storeName); }
      };
      o.onsuccess = function () { resolve(o.result); };
      o.onerror = function () { reject(o.error); };
    });
  }

  function cachedDb(idb, dbName, storeName) {
    var dbPromise = null;
    function reset() { dbPromise = null; }
    function getDb() {
      if (!dbPromise) {
        dbPromise = open(idb, dbName, storeName).then(function (db) {
          db.onclose = reset;
          db.onversionchange = function () {
            reset();
            try { db.close(); } catch (e) {}
          };
          return db;
        }, function (err) {
          reset();
          throw err;
        });
      }
      return dbPromise;
    }
    return function withDb(fn) {
      return getDb().then(function (db) {
        try {
          return fn(db);
        } catch (e) {
          reset();
          return getDb().then(fn);
        }
      });
    };
  }

  function idbBackend(idb, dbName, storeName) {
    var withDb = cachedDb(idb, dbName, storeName);
    return {
      get: function (key) {
        return withDb(function (db) {
          var tx = db.transaction(storeName, "readonly");
          return req(tx.objectStore(storeName).get(key)).then(function (v) {
            if (v === undefined || v === null) { return null; }
            if (typeof Blob !== "undefined" && v instanceof Blob) {
              return v.arrayBuffer().then(function (b) { return new Uint8Array(b); });
            }
            return v instanceof Uint8Array ? v : new Uint8Array(v);
          });
        });
      },
      put: function (key, bytes) {
        var payload = (typeof Blob !== "undefined") ? new Blob([bytes]) : bytes;
        return withDb(function (db) {
          return new Promise(function (resolve, reject) {
            var tx = db.transaction(storeName, "readwrite");
            var os = tx.objectStore(storeName);
            os.clear();
            os.put(payload, key);
            tx.oncomplete = function () { resolve(); };
            tx.onerror = function () { reject(tx.error); };
            tx.onabort = function () { reject(tx.error); };
          });
        });
      },
      clear: function () {
        return withDb(function (db) {
          return new Promise(function (resolve, reject) {
            var tx = db.transaction(storeName, "readwrite");
            tx.objectStore(storeName).clear();
            tx.oncomplete = function () { resolve(); };
            tx.onerror = function () { reject(tx.error); };
            tx.onabort = function () { reject(tx.error); };
          });
        });
      }
    };
  }

  function idbBackendMulti(idb, dbName, storeName) {
    var withDb = cachedDb(idb, dbName, storeName);
    return {
      get: function (key) {
        return withDb(function (db) {
          var tx = db.transaction(storeName, "readonly");
          return req(tx.objectStore(storeName).get(key)).then(function (v) {
            if (v === undefined || v === null) { return null; }
            if (typeof Blob !== "undefined" && v instanceof Blob) {
              return v.arrayBuffer().then(function (b) { return new Uint8Array(b); });
            }
            return v instanceof Uint8Array ? v : new Uint8Array(v);
          });
        });
      },
      put: function (key, bytes) {
        var payload = (typeof Blob !== "undefined") ? new Blob([bytes]) : bytes;
        return withDb(function (db) {
          return new Promise(function (resolve, reject) {
            var tx = db.transaction(storeName, "readwrite");
            tx.objectStore(storeName).put(payload, key);
            tx.oncomplete = function () { resolve(); };
            tx.onerror = function () { reject(tx.error); };
            tx.onabort = function () { reject(tx.error); };
          });
        });
      },
      keys: function () {
        return withDb(function (db) {
          var tx = db.transaction(storeName, "readonly");
          return req(tx.objectStore(storeName).getAllKeys()).then(function (ks) {
            return ks || [];
          });
        });
      },
      del: function (key) {
        return withDb(function (db) {
          return new Promise(function (resolve, reject) {
            var tx = db.transaction(storeName, "readwrite");
            tx.objectStore(storeName).delete(key);
            tx.oncomplete = function () { resolve(); };
            tx.onerror = function () { reject(tx.error); };
            tx.onabort = function () { reject(tx.error); };
          });
        });
      }
    };
  }

  function hydrateAssets(store, key, coldInflate) {
    if (!store) { return Promise.resolve(coldInflate()); }
    return Promise.resolve()
      .then(function () { return store.get(key); })
      .catch(function () { return null; })
      .then(function (cached) {
        if (cached && cached.length) { return cached; }
        return Promise.resolve(coldInflate()).then(function (bytes) {
          try { Promise.resolve(store.put(key, bytes)).catch(function () {}); } catch (e) {}
          return bytes;
        });
      });
  }

  function hydrateThen(root, key, continuation) {
    var done = function () { try { continuation(); } catch (e) {} };
    var store = (root && root.__minecraftInstallBlobStore) ? root.__minecraftInstallBlobStore() : null;
    if (!store) { done(); return; }
    Promise.resolve().then(function () { return store.get(key); })
      .then(function (cached) { if (cached && cached.length) { root.__minecraftAssetsBinRaw = cached; } })
      .catch(function () {})
      .then(done);
  }

  function fetchThenCache(store, key, urls, opts) {
    opts = opts || {};
    return Promise.resolve(store ? store.get(key) : null)
      .catch(function () { return null; })
      .then(function (cached) {
        if (cached && cached.length) { return cached; }
        return fetchFirst(urls, 0, !!opts.decompress).then(function (bytes) {
          if (store) { try { Promise.resolve(store.put(key, bytes)).catch(function () {}); } catch (e) {} }
          return bytes;
        });
      });
  }

  var GATEWAY_TIMEOUT_MS = 12000;

  function fetchFirst(urls, i, decompress) {
    if (!urls || i >= urls.length) { return Promise.reject(new Error("no gateway succeeded")); }
    return fetchOne(urls[i], decompress).catch(function (e) {
      if (i + 1 >= urls.length) { throw e; }
      return fetchFirst(urls, i + 1, decompress);
    });
  }

  function fetchOne(url, decompress) {
    var ctrl = (typeof AbortController !== "undefined") ? new AbortController() : null;
    var timer = ctrl ? setTimeout(function () { ctrl.abort(); }, GATEWAY_TIMEOUT_MS) : null;
    return fetch(url, ctrl ? { signal: ctrl.signal } : undefined).then(function (res) {
      if (timer) { clearTimeout(timer); timer = null; }
      if (!res.ok) { throw new Error("HTTP " + res.status + " for " + url); }
      var stream = res.body;
      if (decompress) {
        if (typeof DecompressionStream === "undefined") { throw new Error("DecompressionStream unavailable"); }
        stream = res.body.pipeThrough(new DecompressionStream("gzip"));
      }
      return new Response(stream).arrayBuffer().then(function (b) { return new Uint8Array(b); });
    }).catch(function (e) {
      if (timer) { clearTimeout(timer); timer = null; }
      throw e;
    });
  }

  function prune(store, keep) {
    if (!store || !store.keys || !store.del) { return Promise.resolve(); }
    var set = {};
    for (var i = 0; i < keep.length; i++) { set[keep[i]] = true; }
    return Promise.resolve(store.keys()).then(function (ks) {
      var dels = [];
      for (var j = 0; j < ks.length; j++) {
        if (!set[ks[j]]) { dels.push(store.del(ks[j])); }
      }
      return Promise.all(dels);
    }).catch(function () {});
  }

  function mapBackend() {
    var m = new Map();
    return {
      get: function (k) { return Promise.resolve(m.has(k) ? m.get(k) : null); },
      put: function (k, v) { m.clear(); m.set(k, v); return Promise.resolve(); },
      clear: function () { m.clear(); return Promise.resolve(); },
      _map: m
    };
  }

  return { idbBackend: idbBackend, idbBackendMulti: idbBackendMulti, hydrateAssets: hydrateAssets,
           hydrateThen: hydrateThen, fetchThenCache: fetchThenCache, prune: prune, mapBackend: mapBackend };
});
