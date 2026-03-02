(() => {
  const ENGINE_HASH_KEY = "fp_engine";
  const ENHANCED_VALUE = "yale_smart";
  const BASELINE_INTEL_VALUE = "baseline_intel";
  const CONTROL_VALUE = "clean_control_v1";
  const LEGACY_COMPAT_VALUE = "legacy";
  const ENHANCED_FLAG = "__fp_enhanced";
  const HW_FP_FLAG = "__fp_hardware_fingerprinting_detected";
  const HW_FP_MESSAGE_TYPE = "__fp_hw_fp_signal_v1";
  const HOOK_SENTINEL = "__fp_canvas_hook_installed";
  const PAGE_HOOK_SENTINEL = "__fp_canvas_page_hook_injected";

  function parseEngineFromHash(hash) {
    const raw = String(hash || "").replace(/^#/, "");
    if (!raw) return null;
    const parts = raw.split("&");
    for (const part of parts) {
      if (!part) continue;
      const [rawKey, rawValue = ""] = part.split("=");
      if (decodeURIComponent(rawKey || "").toLowerCase() !== ENGINE_HASH_KEY) continue;
      return decodeURIComponent(rawValue || "").toLowerCase();
    }
    return null;
  }

  function scrubEngineHashToken() {
    const raw = String(window.location.hash || "").replace(/^#/, "");
    if (!raw) return;
    const kept = raw
      .split("&")
      .filter((part) => part)
      .filter((part) => {
        const key = decodeURIComponent(part.split("=")[0] || "").toLowerCase();
        return key !== ENGINE_HASH_KEY;
      });
    const nextHash = kept.length > 0 ? `#${kept.join("&")}` : "";
    const cleanUrl = `${window.location.pathname}${window.location.search}${nextHash}`;
    window.history.replaceState(null, "", cleanUrl);
  }

  function markHardwareFingerprinting() {
    window[HW_FP_FLAG] = true;
    window.postMessage({ type: HW_FP_MESSAGE_TYPE }, window.location.origin);
  }

  function installCanvasObserverInContentRealm() {
    if (window[HOOK_SENTINEL]) return;
    const prototype = window.HTMLCanvasElement?.prototype;
    if (!prototype || typeof prototype.toDataURL !== "function") return;
    const original = prototype.toDataURL;
    prototype.toDataURL = function wrappedToDataURL(...args) {
      markHardwareFingerprinting();
      return original.apply(this, args);
    };
    window[HOOK_SENTINEL] = true;
  }

  function installCanvasObserverFallbackInPageRealm() {
    if (window[PAGE_HOOK_SENTINEL]) return;
    const script = document.createElement("script");
    script.textContent = `
      (() => {
        if (window.__fpPageCanvasHookInstalled) return;
        const proto = window.HTMLCanvasElement && window.HTMLCanvasElement.prototype;
        if (!proto || typeof proto.toDataURL !== "function") return;
        const original = proto.toDataURL;
        proto.toDataURL = function(...args) {
          try {
            window.postMessage({ type: "${HW_FP_MESSAGE_TYPE}" }, window.location.origin);
          } catch (e) {}
          return original.apply(this, args);
        };
        window.__fpPageCanvasHookInstalled = true;
      })();
    `;
    (document.documentElement || document.head || document.body).appendChild(script);
    script.remove();
    window[PAGE_HOOK_SENTINEL] = true;
  }

  const engine = parseEngineFromHash(window.location.hash);
  const isKnownProfile =
    engine === ENHANCED_VALUE ||
    engine === BASELINE_INTEL_VALUE ||
    engine === CONTROL_VALUE ||
    engine === LEGACY_COMPAT_VALUE;
  const enhanced = engine === ENHANCED_VALUE || engine === BASELINE_INTEL_VALUE;
  window[ENHANCED_FLAG] = enhanced;
  window[HW_FP_FLAG] = false;

  if (isKnownProfile) {
    scrubEngineHashToken();
  }

  if (enhanced) {
    installCanvasObserverInContentRealm();
    installCanvasObserverFallbackInPageRealm();
  }
})();
