(() => {
  const PROXY_CHANNEL = "com.fairprice.extractor.proxy";

  if (globalThis.__fpProxyOnRequestInstalled) {
    return;
  }
  globalThis.__fpProxyOnRequestInstalled = true;

  browser.proxy.onRequest.addListener(
    async function () {
      try {
        const r = await browser.runtime.sendNativeMessage(PROXY_CHANNEL, { type: "GET_ROUTE" });
        if (r && r.enabled) {
          return {
            type: "socks",
            host: r.host || "pr.oxylabs.io",
            port: Number(r.port) || 7777,
            proxyDNS: true,
          };
        }
      } catch (error) {
        console.log("[FairPrice Extractor] GET_ROUTE failed; using direct.", error);
      }
      return { type: "direct" };
    },
    { urls: ["<all_urls>"] },
  );

  console.log("[FairPrice Extractor] proxy.onRequest registered (GET_ROUTE).");
})();
