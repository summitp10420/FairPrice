setTimeout(() => {
  const payload = {
    type: "PRICE_EXTRACT",
    priceCents: 7500,
  };

  console.log("[FairPrice extractor] sending native message", payload);
  browser.runtime
    .sendNativeMessage("extractor@fairprice.com", payload)
    .then((response) => {
      console.log("[FairPrice extractor] native message success", response);
    })
    .catch((error) => {
      console.error("[FairPrice extractor] native message failed", error);
    });
}, 3000);
