(() => {
  const NATIVE_CHANNEL = "com.fairprice.extractor";
  const MAX_ATTEMPTS = 60;
  const RETRY_DELAY_MS = 500;
  let didSend = false;
  let attempts = 0;
  let observer = null;

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

  function sniffTactics() {
    const tactics = [];
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
    if (hiddenCanvas) tactics.push("hidden_canvas");

    const cookie = document.cookie.toLowerCase();
    const trackingTokens = ["_fbp", "_ga", "_gid", "adroll", "doubleclick", "trk", "track"];
    if (trackingTokens.some((token) => cookie.includes(token))) {
      tactics.push("cookie_tracking");
    }

    return tactics;
  }

  function buildPayload() {
    const jsonLdCents = extractJsonLdPriceCents();
    const fallbackCents = jsonLdCents == null ? extractPelicanFallbackPriceCents() : null;
    const resolvedPrice = jsonLdCents ?? fallbackCents;
    if (resolvedPrice == null) return null;

    return {
      type: "PRICE_EXTRACT",
      priceCents: resolvedPrice,
      detectedTactics: sniffTactics(),
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
