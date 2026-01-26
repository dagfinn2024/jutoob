(function () {

  function removeAppPromo() {
    const selectors = [
      'ytm-app-promo',
      'ytm-open-app',
      'a[href^="intent://"]',
      '[aria-label*="Open App"]',
      '.ytm-app-promo',
      'ytm-mobile-topbar-renderer ytm-app-promo'
    ];

    selectors.forEach(sel => {
      document.querySelectorAll(sel).forEach(el => el.remove());
    });
  }

  // Initial kill (runs extremely early)
  removeAppPromo();

  // MutationObserver to prevent reinsertion
  const observer = new MutationObserver(() => {
    removeAppPromo();
  });

  observer.observe(document.documentElement, {
    childList: true,
    subtree: true
  });

})();