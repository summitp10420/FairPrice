setTimeout(() => {
  browser.runtime.sendNativeMessage("extractor@fairprice.com", {
    type: "PRICE_EXTRACT",
    priceCents: 7500,
  });
}, 3000);
