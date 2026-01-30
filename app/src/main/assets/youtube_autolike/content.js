(function() {
    "use strict";

    const LIKE_SELECTORS = [
        "ytd-watch-metadata #top-level-buttons-computed ytd-toggle-button-renderer:first-child button",
        "#segmented-like-button button",
        "like-button-view-model button",
        "button[aria-label*='like this video']"
    ];

    const SUB_SELECTORS = [
        "ytd-watch-metadata #subscribe-button button",
        "#subscribe-button button",
        "ytd-subscribe-button-renderer button",
        "ytm-subscribe-button-renderer button"
    ];

    function findElement(selectors) {
        for (const selector of selectors) {
            const el = document.querySelector(selector);
            if (el && el.offsetWidth > 0 && el.offsetHeight > 0) return el;
        }
        return null;
    }

    function isSubscribed(btn) {
        if (!btn) return false;

        // 1. Check attributes
        if (btn.hasAttribute("subscribed")) return true;

        // 2. Check classes (common in new UI)
        if (btn.classList.contains("yt-spec-button-shape-next--tonal")) return true;

        // 3. Check text content
        const text = btn.innerText || btn.textContent || "";
        if (text.trim().toLowerCase().includes("subscribed")) return true;

        // 4. Check aria-label
        const ariaLabel = btn.getAttribute("aria-label") || "";
        if (ariaLabel.toLowerCase().includes("unsubscribe")) return true;

        return false;
    }

    function isLiked(btn) {
        if (!btn) return false;

        // 1. Check aria-pressed
        if (btn.getAttribute("aria-pressed") === "true") return true;

        // 2. Check classes
        if (btn.classList.contains("style-default-active")) return true;

        // 3. Check aria-label (e.g. "Remove like")
        const ariaLabel = btn.getAttribute("aria-label") || "";
        if (ariaLabel.toLowerCase().includes("remove like")) return true;

        return false;
    }

    function checkAndLike() {
        if (!window.location.pathname.startsWith("/watch")) {
            return;
        }

        const subButton = findElement(SUB_SELECTORS);
        const likeButton = findElement(LIKE_SELECTORS);

        if (!subButton) {
            return;
        }
        if (!likeButton) {
            return;
        }

        const subscribed = isSubscribed(subButton);
        const liked = isLiked(likeButton);

        if (subscribed && !liked) {
            likeButton.click();
        }
    }

    // Run more frequently at the start of a page load
    let checkCount = 0;
    const initialCheck = setInterval(() => {
        checkAndLike();
        if (++checkCount > 10) clearInterval(initialCheck);
    }, 120000);

    // Periodic check every minute as requested
    setInterval(checkAndLike, 160000);

    // Handle YouTube's navigation
    window.addEventListener("yt-navigate-finish", () => {
        checkCount = 0;
        setTimeout(checkAndLike, 192000);
    });

    const PREFIX = "JUTOOB";
    let userPaused = false;

    function nowTitle() {
        document.title = PREFIX + Date.now();
    }

    function videoTitle(v) {
        const time = (v && !isNaN(v.currentTime)) ? Math.floor(v.currentTime * 1000) : Date.now();
        document.title = PREFIX + time;
    }

    function ensurePlaying(v) {
        if (!v || v.ended) return;
        if (v.readyState >= 2 && (v.paused || v.ended)) {
            v.play().catch(() => {
                v.muted = true;
                v.play().then(() => {
                    setTimeout(() => { v.muted = false; }, 250);
                }).catch(e => console.log("Playback failed:", e));
            });
        }
    }

    document.addEventListener("pause", e => {
        if (e.target instanceof HTMLVideoElement) {
          userPaused = true;
        }
      }, true);

    document.addEventListener("play", e => {
        if (e.target instanceof HTMLVideoElement) {
          userPaused = false;
        }
      }, true);

    document.addEventListener("visibilitychange", () => {
        const v = document.querySelector("video");
        if (document.visibilityState === "hidden" && v && !userPaused) {
            ensurePlaying(v);
        }
    });

    function isLoginPage() {
        return window.location.hostname.includes("accounts.google.com") ||
               !!document.querySelector('form[action*="signin"]');
    }

    setInterval(() => {
        if (isLoginPage()) {
            document.title = "JUTOOB_LOGIN" + Date.now();
            return;
        }
            const v = document.querySelector("video");
        if (!v || !v.src || v.src === "" || v.readyState === 0) {
            nowTitle();
            return;
        }
        if (!v.paused && !v.ended) {
            videoTitle(v);
        } else {
            nowTitle();
            if (!userPaused) {
              ensurePlaying(v);
            }
        }
    }, 1000);

})();