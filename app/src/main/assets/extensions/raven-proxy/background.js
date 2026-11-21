/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

"use strict";

/* globals browser */

// The app sends {type: "config", profiles: {<contextId>: {type, host, port}}} whenever the settings change.
const port = browser.runtime.connectNative("ravenProxy");
port.onMessage.addListener(async message => {
  if (message && message.type === "config") {
    try {
      await browser.ravenProxy.setConfig(JSON.stringify(message.profiles || {}));
      port.postMessage({ type: "ack" });
    } catch (e) {
      console.error("raven-proxy: setConfig failed", e);
      port.postMessage({ type: "error", message: String(e) });
    }
  }
});
