// Gallery client-side behaviour: persist the chosen theme (overriding the OS default), and filter
// the cards as you type, hiding categories that end up empty.
(function () {
  var root = document.documentElement;
  var saved = localStorage.getItem('theme');
  if (saved) {
    root.setAttribute('data-theme', saved);
  }
  var toggle = document.getElementById('theme-toggle');
  if (toggle) {
    toggle.addEventListener('click', function () {
      var next = root.getAttribute('data-theme') === 'dark' ? 'light' : 'dark';
      root.setAttribute('data-theme', next);
      localStorage.setItem('theme', next);
    });
  }
  var search = document.getElementById('search');
  if (search) {
    search.addEventListener('input', function () {
      var query = search.value.toLowerCase();
      document.querySelectorAll('section.cat').forEach(function (section) {
        var any = false;
        section.querySelectorAll('a.card').forEach(function (card) {
          var match = card.getAttribute('data-name').indexOf(query) >= 0;
          card.style.display = match ? '' : 'none';
          if (match) {
            any = true;
          }
        });
        section.style.display = any ? '' : 'none';
      });
    });
  }
})();
