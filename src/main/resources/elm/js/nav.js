// Marks the sidebar link for the current page as active. The sidebar markup is identical on every
// sub-page (written once as nav.html); this is the only per-page bit, resolved from the URL so the
// fragment itself stays static.
(function () {
  var here = location.pathname.split("/").pop() || "index.html";
  var links = document.querySelectorAll(".sidebar a[href]");
  for (var i = 0; i < links.length; i++) {
    if (links[i].getAttribute("href") === here) {
      links[i].classList.add("active");
      links[i].setAttribute("aria-current", "page");
    }
  }
})();
