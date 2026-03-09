(() => {
  const NATIVE_CHANNEL = "com.fairprice.extractor";
  const MAX_ATTEMPTS = 60;
  const RETRY_DELAY_MS = 500;
  const ENHANCED_FLAG = "__fp_enhanced";
  const HW_FP_FLAG = "__fp_hardware_fingerprinting_detected";
  const UA_FP_FLAG = "__fp_ua_profiling_detected";
  const HW_FP_MESSAGE_TYPE = "__fp_hw_fp_signal_v1";
  const UA_FP_MESSAGE_TYPE = "__fp_ua_profiling_signal_v1";
  let didSend = false;
  let attempts = 0;
  let observer = null;
  let hardwareFingerprintingSeen = false;
  let uaProfilingSeen = false;

  function handleSignalMessage(event) {
    if (event.source !== window) return;
    const payload = event.data;
    if (!payload || typeof payload !== "object") return;
    if (payload.type === HW_FP_MESSAGE_TYPE) {
      hardwareFingerprintingSeen = true;
      window[HW_FP_FLAG] = true;
    } else if (payload.type === UA_FP_MESSAGE_TYPE) {
      uaProfilingSeen = true;
      window[UA_FP_FLAG] = true;
    }
  }

  window.addEventListener("message", handleSignalMessage);

  function normalizePriceToCents(value) {
    if (!value) return null;
    const cleaned = String(value).trim().replace(/[^0-9.,]/g, "");
    if (!cleaned) return null;

    const hasDot = cleaned.includes(".");
    const hasComma = cleaned.includes(",");
    let normalized = cleaned;

    if (hasDot && hasComma) {
      normalized = cleaned.replace(/,/g, "");
    } else if (!hasDot && hasComma) {
      normalized = cleaned.replace(",", ".");
    } else {
      normalized = cleaned.replace(/,/g, "");
    }

    const numeric = Number.parseFloat(normalized);
    if (!Number.isFinite(numeric) || numeric <= 0) return null;
    return Math.round(numeric * 100);
  }

  function extractJsonLdPriceCents() {
    const scripts = document.querySelectorAll('script[type="application/ld+json"]');
    for (const script of scripts) {
      try {
        const raw = script.textContent || "";
        if (!raw.trim()) continue;
        const json = JSON.parse(raw);
        const queue = Array.isArray(json) ? [...json] : [json];
        while (queue.length > 0) {
          const node = queue.shift();
          if (!node || typeof node !== "object") continue;

          const type = Array.isArray(node["@type"]) ? node["@type"] : [node["@type"]];
          const isProduct = type.some((entry) => String(entry).toLowerCase() === "product");
          if (isProduct) {
            const offers = Array.isArray(node.offers) ? node.offers : [node.offers];
            for (const offer of offers) {
              if (!offer || typeof offer !== "object") continue;
              const price = offer.price ?? offer.lowPrice ?? offer.highPrice;
              const cents = normalizePriceToCents(price);
              if (cents != null) return cents;
            }
          }

          for (const value of Object.values(node)) {
            if (Array.isArray(value)) {
              queue.push(...value);
            } else if (value && typeof value === "object") {
              queue.push(value);
            }
          }
        }
      } catch (error) {
        console.warn("[FairPrice extractor] Failed parsing JSON-LD script", error);
      }
    }
    return null;
  }

  function isAmazonProductPage() {
    const host = String(window.location.host || "").toLowerCase();
    if (host.includes("amazon.") || host === "a.co" || host.endsWith(".a.co")) {
      return true;
    }
    return Boolean(
      document.querySelector("#dp") ||
        document.querySelector("#productTitle") ||
        document.querySelector("#corePrice_feature_div"),
    );
  }

  function extractAmazonCandidateCents(selector) {
    const nodes = document.querySelectorAll(selector);
    for (const node of nodes) {
      const cents = normalizePriceToCents(node.textContent || node.getAttribute("content"));
      if (cents != null) return cents;
    }
    return null;
  }

  function extractAmazonPriceCents() {
    const selectors = [
      "#corePrice_feature_div .a-price .a-offscreen",
      "#corePriceDisplay_desktop_feature_div .a-price .a-offscreen",
      "#apex_desktop .a-price .a-offscreen",
      "#price_inside_buybox",
      "#newBuyBoxPrice",
      "#priceblock_ourprice",
      "#priceblock_dealprice",
      ".a-price.aok-align-center .a-offscreen",
      ".a-price .a-offscreen",
    ];
    for (const selector of selectors) {
      const cents = extractAmazonCandidateCents(selector);
      if (cents != null) return cents;
    }
    return null;
  }

  function extractPelicanFallbackPriceCents() {
    const selectors = [
      ".price",
      ".our_price",
      ".product-price",
      "[itemprop='price']",
      ".js-price",
    ];

    for (const selector of selectors) {
      const node = document.querySelector(selector);
      if (!node) continue;
      const cents = normalizePriceToCents(node.textContent || node.getAttribute("content"));
      if (cents != null) return cents;
    }

    return null;
  }

  function detectWafVendorTactics() {
    const tactics = new Set();
    const cookie = String(document.cookie || "").toLowerCase();

    const hasPerimeterX =
      cookie.includes("_px") ||
      cookie.includes("pxcts") ||
      cookie.includes("_pxvid") ||
      typeof window._pxAppId !== "undefined" ||
      typeof window._pxvid !== "undefined" ||
      typeof window.PerimeterX !== "undefined" ||
      document.querySelector("script[src*='perimeterx']") != null ||
      document.querySelector("script[src*='humansecurity']") != null;
    if (hasPerimeterX) tactics.add("vendor_perimeterx");

    const hasDatadome =
      cookie.includes("datadome") ||
      typeof window.datadome !== "undefined" ||
      typeof window.__ddg1_ !== "undefined" ||
      document.querySelector("script[src*='datadome']") != null ||
      document.querySelector("script[src*='captcha-delivery.com']") != null;
    if (hasDatadome) tactics.add("vendor_datadome");

    const hasAkamai =
      cookie.includes("_abck") ||
      cookie.includes("bm_sz") ||
      cookie.includes("ak_bmsc") ||
      typeof window.bmak !== "undefined" ||
      document.querySelector("script[src*='akamai']") != null ||
      document.querySelector("script[src*='edgekey']") != null;
    if (hasAkamai) tactics.add("vendor_akamai");

    const hasCloudflare =
      cookie.includes("__cf_bm") ||
      cookie.includes("cf_clearance") ||
      cookie.includes("cf_chl_") ||
      typeof window.__cfRLUnblockHandlers !== "undefined" ||
      document.querySelector("script[src*='cloudflare']") != null;
    if (hasCloudflare) tactics.add("vendor_cloudflare");

    return Array.from(tactics);
  }

  function detectWafBlock() {
    const tactics = new Set();
    const title = String(document.title || "").toLowerCase();
    const bodyText = String(document.body?.textContent || "").toLowerCase();

    const perimeterxBlocked =
      document.querySelector("#px-captcha, .px-captcha, [id*='px-captcha']") != null ||
      document.querySelector("iframe[src*='perimeterx']") != null;
    if (perimeterxBlocked) tactics.add("block_perimeterx");

    const datadomeBlocked =
      document.querySelector("#datadome-captcha, .dd-captcha, [id*='datadome']") != null ||
      title.includes("datadome") ||
      bodyText.includes("datadome");
    if (datadomeBlocked) tactics.add("block_datadome");

    const cloudflareBlocked =
      document.querySelector("#challenge-form, #cf-challenge-running, .cf-challenge") != null ||
      title.includes("attention required") ||
      title.includes("just a moment") ||
      bodyText.includes("checking your browser before accessing");
    if (cloudflareBlocked) tactics.add("block_cloudflare");

    const akamaiBlocked =
      bodyText.includes("access denied") &&
      bodyText.includes("reference #") &&
      (document.querySelector("[id*='akamai']") != null ||
        document.querySelector("script[src*='akamai']") != null);
    if (akamaiBlocked) tactics.add("block_akamai");

    return {
      blocked: tactics.size > 0,
      tactics: Array.from(tactics),
    };
  }

  function sniffTactics() {
    const tactics = new Set();
    const hiddenCanvas = Array.from(document.querySelectorAll("canvas")).some((canvas) => {
      const style = window.getComputedStyle(canvas);
      const width = canvas.width || canvas.clientWidth || 0;
      const height = canvas.height || canvas.clientHeight || 0;
      return (
        style.display === "none" ||
        style.visibility === "hidden" ||
        Number.parseFloat(style.opacity || "1") === 0 ||
        width <= 1 ||
        height <= 1
      );
    });
    if (hiddenCanvas) tactics.add("hidden_canvas");

    const cookie = document.cookie.toLowerCase();
    const trackingTokens = ["_fbp", "_ga", "_gid", "adroll", "doubleclick", "trk", "track"];
    if (trackingTokens.some((token) => cookie.includes(token))) {
      tactics.add("cookie_tracking");
    }

    for (const vendorTactic of detectWafVendorTactics()) {
      tactics.add(vendorTactic);
    }

    const enhancedMode = window[ENHANCED_FLAG] === true;
    if (enhancedMode) {
      const hasSurveillanceMarker =
        typeof window.FS !== "undefined" ||
        typeof window._hjSettings !== "undefined" ||
        typeof window.datadome !== "undefined" ||
        typeof window.__ddg1_ !== "undefined" ||
        document.querySelector("script[src*='datadome']") != null;
      if (hasSurveillanceMarker) {
        tactics.add("surveillance_active");
      }

      const hardwareFingerprintingDetectedNow =
        window[HW_FP_FLAG] === true || hardwareFingerprintingSeen === true;
      if (hardwareFingerprintingDetectedNow) {
        tactics.add("hardware_fingerprinting");
      }

      const uaProfilingDetectedNow =
        window[UA_FP_FLAG] === true || uaProfilingSeen === true;
      if (uaProfilingDetectedNow) {
        tactics.add("user_agent_profiling");
      }
    }

    return Array.from(tactics);
  }

  function buildPayload() {
    let extractionPath = "none";
    const detectedTactics = new Set(sniffTactics());

    const wafBlock = detectWafBlock();
    if (wafBlock.blocked) {
      for (const tactic of wafBlock.tactics) {
        detectedTactics.add(tactic);
      }
      return {
        type: "PRICE_EXTRACT",
        priceCents: 0,
        detectedTactics: Array.from(detectedTactics),
        debugExtractionPath: "waf_block",
      };
    }

    const amazonCents = isAmazonProductPage() ? extractAmazonPriceCents() : null;
    if (amazonCents != null) {
      extractionPath = "amazon_dom";
    }

    const jsonLdCents = amazonCents == null ? extractJsonLdPriceCents() : null;
    if (jsonLdCents != null) {
      extractionPath = "json_ld";
    }

    const fallbackCents =
      amazonCents == null && jsonLdCents == null ? extractPelicanFallbackPriceCents() : null;
    if (fallbackCents != null) {
      extractionPath = "fallback_dom";
    }

    const resolvedPrice = amazonCents ?? jsonLdCents ?? fallbackCents;
    if (resolvedPrice == null) return null;

    return {
      type: "PRICE_EXTRACT",
      priceCents: resolvedPrice,
      detectedTactics: Array.from(detectedTactics),
      debugExtractionPath: extractionPath,
    };
  }

  function disconnectObserver() {
    if (observer) {
      observer.disconnect();
      observer = null;
    }
  }

  function sendIfReady() {
    if (didSend) return;
    attempts += 1;

    const payload = buildPayload();
    if (!payload) {
      if (attempts >= MAX_ATTEMPTS) {
        console.warn("[FairPrice extractor] Price not found before retry limit.");
        disconnectObserver();
      }
      return;
    }

    didSend = true;
    disconnectObserver();
    console.log("[FairPrice extractor] sending native message", payload);
    browser.runtime
      .sendNativeMessage(NATIVE_CHANNEL, payload)
      .then((response) => {
        console.log("[FairPrice extractor] native message success", response);
      })
      .catch((error) => {
        console.error("[FairPrice extractor] native message failed", error);
      });
  }

  function scheduleRetry() {
    if (didSend) return;
    setTimeout(() => {
      sendIfReady();
      if (!didSend && attempts < MAX_ATTEMPTS) {
        scheduleRetry();
      }
    }, RETRY_DELAY_MS);
  }

  observer = new MutationObserver(() => {
    sendIfReady();
  });
  observer.observe(document.documentElement, {
    childList: true,
    subtree: true,
  });

  window.addEventListener("load", sendIfReady, { once: true });
  document.addEventListener("readystatechange", () => {
    if (document.readyState === "interactive" || document.readyState === "complete") {
      sendIfReady();
    }
  });

  sendIfReady();
  scheduleRetry();
})();
