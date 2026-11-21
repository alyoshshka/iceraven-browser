/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

"use strict";

/* global ExtensionAPI, Services, Cc, Ci */

// Per-profile proxy. A channel filter looks up the profile (GeckoView context id) of every request:
// - no profile (Default): the default proxy info, i.e. direct;
// - profile with a proxy: that proxy, never a list containing DIRECT (fail closed);
// - anything unexpected for a profile request: a dead proxy, so the request fails instead of going direct.

const CONFIG_PREF = "raven.proxy.config";
const DEAD = { type: "socks", host: "127.0.0.1", port: 1 };

const HARDENING = {
  "network.dns.disablePrefetch": true,
  "network.dns.disablePrefetchFromHTTPS": true,
  "network.predictor.enabled": false,
  "network.prefetch-next": false,
  "network.http.speculative-parallel-limit": 0,
  "network.proxy.failover_direct": false,
  "media.peerconnection.ice.proxy_only_if_behind_proxy": true,
  "media.peerconnection.ice.default_address_only": true,
};

let profiles = null; // null until a config was read or received
let filter = null;

function setPref(name, value) {
  if (typeof value === "boolean") {
    Services.prefs.setBoolPref(name, value);
  } else {
    Services.prefs.setIntPref(name, value);
  }
}

function loadPersisted() {
  try {
    const json = Services.prefs.getStringPref(CONFIG_PREF, "");
    if (json) {
      profiles = JSON.parse(json) || {};
    }
  } catch (e) {
    console.error("raven-proxy: bad persisted config", e);
  }
}

function proxyInfoFor(pps, spec) {
  if (spec.type === "socks") {
    return pps.newProxyInfo(
      "socks",
      spec.host,
      spec.port,
      "",
      "",
      Ci.nsIProxyInfo.TRANSPARENT_PROXY_RESOLVES_HOST,
      0xffffffff,
      null
    );
  }
  return pps.newProxyInfo("http", spec.host, spec.port, "", "", 0, 0xffffffff, null);
}

function startFilter() {
  if (filter) {
    return;
  }
  const pps = Cc["@mozilla.org/network/protocol-proxy-service;1"].getService(
    Ci.nsIProtocolProxyService
  );
  filter = {
    applyFilter(channel, defaultProxyInfo, callback) {
      let result = defaultProxyInfo;
      let ctx = "";
      try {
        ctx = channel.loadInfo?.originAttributes?.geckoViewSessionContextId || "";
        if (ctx && ctx !== "gvctxempty") {
          if (!profiles) {
            result = proxyInfoFor(pps, DEAD);
          } else {
            const spec = profiles[ctx];
            if (spec) {
              result = proxyInfoFor(pps, spec);
            }
          }
        }
      } catch (e) {
        if (ctx && ctx !== "gvctxempty") {
          try {
            result = proxyInfoFor(pps, DEAD);
          } catch (e2) {
            result = defaultProxyInfo;
          }
        }
      }
      callback.onProxyFilterResult(result);
    },
  };
  pps.registerChannelFilter(filter, 10000);
}

function stopFilter() {
  if (!filter) {
    return;
  }
  try {
    Cc["@mozilla.org/network/protocol-proxy-service;1"]
      .getService(Ci.nsIProtocolProxyService)
      .unregisterChannelFilter(filter);
  } catch (e) {
    // already gone
  }
  filter = null;
}

let started = false;
function init() {
  if (started) {
    return;
  }
  started = true;
  for (const [name, value] of Object.entries(HARDENING)) {
    try {
      setPref(name, value);
    } catch (e) {
      console.error("raven-proxy: could not set " + name, e);
    }
  }
  loadPersisted();
  startFilter();
}

this.ravenProxy = class extends ExtensionAPI {
  onStartup() {
    init();
  }

  onShutdown() {
    stopFilter();
    started = false;
  }

  getAPI() {
    init(); // no-op for the filter if onStartup already ran
    return {
      ravenProxy: {
        async setConfig(json) {
          const parsed = JSON.parse(json);
          Services.prefs.setStringPref(CONFIG_PREF, json);
          profiles = parsed || {};
        },
      },
    };
  }
};
