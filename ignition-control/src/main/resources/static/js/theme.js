// Theme switcher: one button (#ignThemeToggle) that cycles window.ignThemes, the
// registry defined inline in <head> (fragments/head.html), which also applies the
// saved theme before first paint so there's no flash of the wrong one. The choice
// is kept in localStorage under 'ign-theme', as before.
(function () {
  var btn = document.getElementById('ignThemeToggle');
  var themes = window.ignThemes || [];
  if (!btn || !themes.length) return;
  var icon = btn.querySelector('i');
  var nameEl = document.getElementById('ignThemeName');   // theme name, under the version in the sidebar
  var root = document.documentElement;

  function indexOf(id) {
    for (var i = 0; i < themes.length; i++) if (themes[i].id === id) return i;
    return 0;
  }

  function apply(i) {
    var t = themes[i], next = themes[(i + 1) % themes.length];
    root.setAttribute('data-ign-lamp', t.id);
    root.setAttribute('data-bs-theme', t.scheme);
    root.setAttribute('data-ign-style', t.style);
    icon.className = t.icon;
    btn.title = 'Theme: ' + t.label + ' — click for ' + next.label;
    btn.setAttribute('aria-label', btn.title);
    if (nameEl) nameEl.textContent = t.label;
  }

  btn.addEventListener('click', function () {
    var i = (indexOf(root.getAttribute('data-ign-lamp')) + 1) % themes.length;
    apply(i);
    try { localStorage.setItem('ign-theme', themes[i].id); } catch (e) {}
  });

  apply(indexOf(root.getAttribute('data-ign-lamp')));
})();
