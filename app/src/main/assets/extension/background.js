(() => {
  const PROXY_CHANNEL = "com.fairprice.extractor.proxy";

  // Defer so the Android MessageDelegate can attach in ensureBuiltIn before we query.
  setTimeout(() => {
    browser.runtime
      .sendNativeMessage(PROXY_CHANNEL, { type: "CHECK_PROXY_RUNTIME" })
      .then((response) => {
        if (response && response.isProxyRuntime) {
          console.log("[FairPrice Extractor] SOCKS5 proxy routing enabled for this runtime.");

          browser.proxy.onRequest.addListener(
            function (requestInfo) {
              return {
                type: "socks",
                host: response.host || "pr.oxylabs.io",
                port: Number(response.port) || 7777,
                proxyDNS: true,
              };
            },
            { urls: ["<all_urls>"] },
          );
        }
      })
      .catch((error) => {
        console.log("[FairPrice Extractor] Clear-net runtime. No proxy registered.", error);
      });
  }, 50);
})();
