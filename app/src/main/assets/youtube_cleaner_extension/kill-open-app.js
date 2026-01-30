(function () {

  function removeAppPromo() {
    const selectors = [
      'ytm-app-promo',
      'ytm-open-app',
      'a[href^="intent://"]',
      '[aria-label*="Open App"]',
      '.ytm-app-promo .mobile-topbar-header',
      'ytm-mobile-topbar-renderer ytm-app-promo'
    ];

    selectors.forEach(sel => {
      document.querySelectorAll(sel).forEach(el => el.remove());
    });
  }

  removeAppPromo();

  const observer = new MutationObserver(() => {
    removeAppPromo();
  });
  observer.observe(document.documentElement, {
    childList: true,
    subtree: true
  });
})();