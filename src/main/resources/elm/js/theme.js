// Shared light/dark theme for every page of the site. Applies the saved theme (or the OS
// preference when none is saved) by setting `data-theme` on <html>, and injects a fixed toggle
// button in the top-right corner that flips and persists the choice. Loaded on all pages so the
// theme and its control are identical site-wide; the CSS keys off `[data-theme=dark]`.
(function () {
  var KEY = "theme";
  var root = document.documentElement;

  function osDark() {
    return window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches;
  }
  function saved() {
    try { return localStorage.getItem(KEY); } catch (e) { return null; }
  }
  function current() {
    return root.getAttribute("data-theme") || (saved() || (osDark() ? "dark" : "light"));
  }
  // Apply as early as this script runs so the page paints in the right theme.
  root.setAttribute("data-theme", current());

  function apply(theme) {
    root.setAttribute("data-theme", theme);
    try { localStorage.setItem(KEY, theme); } catch (e) {}
    if (btn) btn.textContent = theme === "dark" ? "☀️" : "\u{1F319}";
  }

  var btn = document.createElement("button");
  btn.id = "theme-toggle";
  btn.type = "button";
  btn.setAttribute("aria-label", "Toggle dark mode");
  btn.setAttribute(
    "style",
    "position:fixed;top:12px;right:14px;z-index:99999;border:none;border-radius:999px;" +
      "width:36px;height:36px;font-size:16px;line-height:1;cursor:pointer;" +
      "background:rgba(127,127,127,0.18);color:inherit;"
  );
  btn.addEventListener("click", function () {
    apply(current() === "dark" ? "light" : "dark");
  });
  function mount() {
    if (document.body && !document.getElementById("theme-toggle")) {
      document.body.appendChild(btn);
      btn.textContent = current() === "dark" ? "☀️" : "\u{1F319}";
    }
  }
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", mount);
  } else {
    mount();
  }
})();
