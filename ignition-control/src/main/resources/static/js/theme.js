// Lamp: cycles cool (dark) -> warm (dark, amber) -> day (light). The initial
// state is set earlier, inline in <head> (fragments/head.html), so there's no
// flash of the wrong theme before this loads.
(function () {
  var btn = document.getElementById('ignThemeToggle');
  if (!btn) return;
  var icon = btn.querySelector('i');
  var root = document.documentElement;
  var STATES = ['cool', 'warm', 'day'];
  var ICONS = { cool: 'bi bi-lightbulb', warm: 'bi bi-lightbulb-fill', day: 'bi bi-sun' };
  var NEXT = { cool: 'warm it up', warm: 'switch to daylight', day: 'back to the stage' };

  function current() {
    var s = root.getAttribute('data-ign-lamp');
    return STATES.indexOf(s) >= 0 ? s : 'cool';
  }

  function apply(s) {
    root.setAttribute('data-ign-lamp', s);
    root.setAttribute('data-bs-theme', s === 'day' ? 'light' : 'dark');
    icon.className = ICONS[s];
    btn.title = 'Lamp: ' + s + ' — click to ' + NEXT[s];
  }

  btn.addEventListener('click', function () {
    var next = STATES[(STATES.indexOf(current()) + 1) % STATES.length];
    apply(next);
    try { localStorage.setItem('ign-theme', next); } catch (e) {}
  });

  apply(current());
})();
