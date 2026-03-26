(() => {
  const ENGINE_HASH_KEY = "fp_engine";
  const CANVAS_SPOOF_KEY = "fp_canvas_spoof";
  const ENHANCED_VALUE = "stealth_max";
  const SNIFFER_INTEL_VALUE = "sniffer_intel";
  const KNOWN_PROFILES = ["stealth_max", "clean_baseline", "shield_basic", "amnesia_standard", "sniffer_intel"];
  const ENHANCED_FLAG = "__fp_enhanced";
  const HW_FP_FLAG = "__fp_hardware_fingerprinting_detected";
  const UA_FP_FLAG = "__fp_ua_profiling_detected";
  const HW_FP_MESSAGE_TYPE = "__fp_hw_fp_signal_v1";
  const UA_FP_MESSAGE_TYPE = "__fp_ua_profiling_signal_v1";
  const HOOK_SENTINEL = "__fp_fp_hook_installed";
  const PAGE_HOOK_SENTINEL = "__fp_fp_page_hook_injected";

  const accessLog = {
    userAgent: false,
    clientHints: false,
    deviceMemory: false,
    screenMetrics: false
  };
  let uaProfilingReported = false;

  function evaluateUaProfiling() {
    if (uaProfilingReported) return;
    if ((accessLog.userAgent || accessLog.clientHints) && accessLog.deviceMemory && accessLog.screenMetrics) {
      uaProfilingReported = true;
      window[UA_FP_FLAG] = true;
      window.postMessage({ type: UA_FP_MESSAGE_TYPE }, window.location.origin);
    }
  }

  function installUaObserverInContentRealm() {
    try {
      const origUA = Object.getOwnPropertyDescriptor(Navigator.prototype, "userAgent");
      if (origUA && origUA.configurable) {
        Object.defineProperty(Navigator.prototype, "userAgent", {
          get: function() {
            accessLog.userAgent = true;
            evaluateUaProfiling();
            return origUA.get.call(this);
          },
          configurable: true,
          enumerable: true
        });
      }
    } catch (e) {}

    try {
      const origDM = Object.getOwnPropertyDescriptor(Navigator.prototype, "deviceMemory");
      if (origDM && origDM.configurable) {
        Object.defineProperty(Navigator.prototype, "deviceMemory", {
          get: function() {
            accessLog.deviceMemory = true;
            evaluateUaProfiling();
            return origDM.get.call(this);
          },
          configurable: true,
          enumerable: true
        });
      }
    } catch (e) {}

    function wrapScreenGetter(origDesc) {
      const origGet = origDesc && origDesc.get;
      if (!origGet) return null;
      return function() {
        accessLog.screenMetrics = true;
        evaluateUaProfiling();
        return origGet.call(this);
      };
    }

    try {
      const origWidth = Object.getOwnPropertyDescriptor(Screen.prototype, "width");
      if (origWidth && origWidth.configurable) {
        const wrapped = wrapScreenGetter(origWidth);
        if (wrapped) {
          Object.defineProperty(Screen.prototype, "width", {
            get: wrapped,
            configurable: true,
            enumerable: true
          });
        }
      }
    } catch (e) {}

    try {
      const origHeight = Object.getOwnPropertyDescriptor(Screen.prototype, "height");
      if (origHeight && origHeight.configurable) {
        const wrappedH = wrapScreenGetter(origHeight);
        if (wrappedH) {
          Object.defineProperty(Screen.prototype, "height", {
            get: wrappedH,
            configurable: true,
            enumerable: true
          });
        }
      }
    } catch (e) {}

    try {
      const origAvailWidth = Object.getOwnPropertyDescriptor(Screen.prototype, "availWidth");
      if (origAvailWidth && origAvailWidth.configurable) {
        const wrappedAW = wrapScreenGetter(origAvailWidth);
        if (wrappedAW) {
          Object.defineProperty(Screen.prototype, "availWidth", {
            get: wrappedAW,
            configurable: true,
            enumerable: true
          });
        }
      }
    } catch (e) {}

    try {
      const origAvailHeight = Object.getOwnPropertyDescriptor(Screen.prototype, "availHeight");
      if (origAvailHeight && origAvailHeight.configurable) {
        const wrappedAH = wrapScreenGetter(origAvailHeight);
        if (wrappedAH) {
          Object.defineProperty(Screen.prototype, "availHeight", {
            get: wrappedAH,
            configurable: true,
            enumerable: true
          });
        }
      }
    } catch (e) {}

    try {
      if (navigator.userAgentData && typeof navigator.userAgentData.getHighEntropyValues === "function") {
        const origGetHighEntropyValues = navigator.userAgentData.getHighEntropyValues.bind(navigator.userAgentData);
        navigator.userAgentData.getHighEntropyValues = function(...args) {
          accessLog.clientHints = true;
          evaluateUaProfiling();
          return origGetHighEntropyValues(...args);
        };
      }
    } catch (e) {}
  }

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

  function parseCanvasSpoofFromHash(hash) {
    const raw = String(hash || "").replace(/^#/, "");
    if (!raw) return false;
    const parts = raw.split("&");
    for (const part of parts) {
      if (!part) continue;
      const [rawKey, rawValue = ""] = part.split("=");
      if (decodeURIComponent(rawKey || "").toLowerCase() !== CANVAS_SPOOF_KEY) continue;
      return decodeURIComponent(rawValue || "").toLowerCase() === "true";
    }
    return false;
  }

  function scrubEngineHashToken() {
    const raw = String(window.location.hash || "").replace(/^#/, "");
    if (!raw) return;
    const kept = raw
      .split("&")
      .filter((part) => part)
      .filter((part) => {
        const key = decodeURIComponent(part.split("=")[0] || "").toLowerCase();
        return key !== ENGINE_HASH_KEY && key !== CANVAS_SPOOF_KEY;
      });
    const nextHash = kept.length > 0 ? `#${kept.join("&")}` : "";
    const cleanUrl = `${window.location.pathname}${window.location.search}${nextHash}`;
    window.history.replaceState(null, "", cleanUrl);
  }

  function markHardwareFingerprinting() {
    if (window[HW_FP_FLAG] === true) return;
    window[HW_FP_FLAG] = true;
    window.postMessage({ type: HW_FP_MESSAGE_TYPE }, window.location.origin);
  }

  function wrapWebGlPrototype(prototype) {
    if (!prototype) return;
    if (typeof prototype.readPixels === "function") {
      const originalReadPixels = prototype.readPixels;
      prototype.readPixels = function wrappedReadPixels(...args) {
        markHardwareFingerprinting();
        return originalReadPixels.apply(this, args);
      };
    }
    if (typeof prototype.getParameter === "function") {
      const originalGetParameter = prototype.getParameter;
      prototype.getParameter = function wrappedGetParameter(...args) {
        markHardwareFingerprinting();
        return originalGetParameter.apply(this, args);
      };
    }
  }

  function installCanvasObserverInContentRealm() {
    if (window[HOOK_SENTINEL]) return;
    const prototype = window.HTMLCanvasElement?.prototype;
    if (prototype && typeof prototype.toDataURL === "function") {
      const original = prototype.toDataURL;
      prototype.toDataURL = function wrappedToDataURL(...args) {
        markHardwareFingerprinting();
        return original.apply(this, args);
      };
    }
    wrapWebGlPrototype(window.WebGLRenderingContext?.prototype);
    wrapWebGlPrototype(window.WebGL2RenderingContext?.prototype);
    window[HOOK_SENTINEL] = true;
  }

  function installCanvasObserverFallbackInPageRealm() {
    if (window[PAGE_HOOK_SENTINEL]) return;
    const script = document.createElement("script");
    script.textContent = `
      (() => {
        if (window.__fpPageFingerprintHookInstalled) return;
        let signaled = false;
        const signal = () => {
          if (signaled) return;
          try {
            window.postMessage({ type: "${HW_FP_MESSAGE_TYPE}" }, window.location.origin);
            signaled = true;
          } catch (e) {}
        };
        const canvasProto = window.HTMLCanvasElement && window.HTMLCanvasElement.prototype;
        if (canvasProto && typeof canvasProto.toDataURL === "function") {
          const originalCanvas = canvasProto.toDataURL;
          canvasProto.toDataURL = function(...args) {
            signal();
            return originalCanvas.apply(this, args);
          };
        }
        const wrapWebGl = (proto) => {
          if (!proto) return;
          if (typeof proto.readPixels === "function") {
            const originalReadPixels = proto.readPixels;
            proto.readPixels = function(...args) {
              signal();
              return originalReadPixels.apply(this, args);
            };
          }
          if (typeof proto.getParameter === "function") {
            const originalGetParameter = proto.getParameter;
            proto.getParameter = function(...args) {
              signal();
              return originalGetParameter.apply(this, args);
            };
          }
        };
        wrapWebGl(window.WebGLRenderingContext && window.WebGLRenderingContext.prototype);
        wrapWebGl(window.WebGL2RenderingContext && window.WebGL2RenderingContext.prototype);
        window.__fpPageFingerprintHookInstalled = true;
      })();
    `;
    (document.documentElement || document.head || document.body).appendChild(script);
    script.remove();
    window[PAGE_HOOK_SENTINEL] = true;
  }

  const engine = parseEngineFromHash(window.location.hash);
  const canvasSpoofFromHash = parseCanvasSpoofFromHash(window.location.hash);
  const isKnownProfile = engine && KNOWN_PROFILES.includes(engine);
  const enhanced = canvasSpoofFromHash || engine === ENHANCED_VALUE || engine === SNIFFER_INTEL_VALUE;

  window[ENHANCED_FLAG] = enhanced;
  window[HW_FP_FLAG] = false;
  window[UA_FP_FLAG] = false;

  if (isKnownProfile || canvasSpoofFromHash) {
    scrubEngineHashToken();
  }

  if (enhanced) {
    installCanvasObserverInContentRealm();
    installCanvasObserverFallbackInPageRealm();
    installUaObserverInContentRealm();
  }
})();
