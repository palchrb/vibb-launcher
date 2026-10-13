// Registers the install-only service worker (templates/sw.js). ES5, so an old WebView parses it (design 31 §5.7).
if ("serviceWorker" in navigator) {
    window.addEventListener("load", function () {
        navigator.serviceWorker.register("/sw.js");
    });
}
