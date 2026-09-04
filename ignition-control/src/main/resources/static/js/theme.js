// Theme toggle button. The initial theme itself is set earlier, inline in
// <head> (fragments/head.html), so there's no flash of the wrong theme
// before this loads.
(function () {
  var btn = document.getElementById('ignThemeToggle');
  if (!btn) return;
  var icon = btn.querySelector('i');

  function sync() {
    var dark = document.documentElement.getAttribute('data-bs-theme') === 'dark';
    icon.className = dark ? 'bi bi-sun' : 'bi bi-moon-stars';
  }

  btn.addEventListener('click', function () {
    var next = document.documentElement.getAttribute('data-bs-theme') === 'dark' ? 'light' : 'dark';
    document.documentElement.setAttribute('data-bs-theme', next);
    try { localStorage.setItem('ign-theme', next); } catch (e) {}
    sync();
  });

  sync();
})();
