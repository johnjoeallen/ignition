// Refresh app state so the status pill and the Start/Stop buttons reflect the
// latest operation, even when another tab changed it.
(function () {
  var rows = document.querySelectorAll('[data-app-status-row]');
  if (!rows.length) return;

  var statusElement = document.querySelector('[data-app-status-url]');
  if (!statusElement) return;
  var url = statusElement.getAttribute('data-app-status-url');

  function setState(app) {
    var channel = app.channel || 'release';
    var row = Array.prototype.find.call(
        document.querySelectorAll('[data-app-status-row="' + CSS.escape(app.name) + '"]'),
        function (el) { return (el.getAttribute('data-app-channel') || 'release') === channel; });
    if (!row) return;

    // the status pill: dot + label, coloured by state (classes, so every theme styles it)
    row.classList.toggle('ign-pill-ok', !!app.running);
    row.classList.toggle('ign-pill-off', !app.running);
    var label = row.querySelector('[data-app-status-label]');
    if (label) label.textContent = app.running ? 'Running' : 'Stopped';
    row.title = app.running ? 'running' : 'stopped';
    // on the team page a stopped app shows a Start button instead of a "Stopped" pill
    if (row.hasAttribute('data-hide-stopped')) row.classList.toggle('d-none', !app.running);

    // the Launch link: greyed while the app isn't answering
    document.querySelectorAll('[data-app-launch][data-app-name="' + CSS.escape(app.name) + '"]')
      .forEach(function (a) {
        if ((a.getAttribute('data-app-channel') || 'release') !== channel) return;
        a.classList.toggle('text-body-secondary', !app.running);
        a.title = app.running ? 'Open in a new tab'
            : 'Not answering right now — opening it will try to wake it if it was idle-stopped';
      });

    document.querySelectorAll('[data-app-action][data-app-name="' + CSS.escape(app.name) + '"]')
      .forEach(function (form) {
        if ((form.getAttribute('data-app-channel') || 'release') !== channel) return;
        var visible = (app.running && form.getAttribute('data-app-action') === 'stop')
            || (!app.running && form.getAttribute('data-app-action') === 'start');
        form.classList.toggle('d-none', !visible);
        form.hidden = !visible;
      });
  }

  function poll() {
    fetch(url, { headers: { 'Accept': 'application/json' }, credentials: 'same-origin' })
      .then(function (response) {
        if (!response.ok) throw new Error('status request failed');
        return response.json();
      })
      .then(function (apps) { apps.forEach(setState); })
      .catch(function () { /* leave the last known state visible on transient errors */ });
  }

  poll();
  window.setInterval(poll, 5000);
  // A background tab's timers are throttled by the browser (to about once a minute), so
  // after Launch opens a new tab this page would show stale state when you come back:
  // refresh the moment it becomes visible / focused again.
  document.addEventListener('visibilitychange', function () { if (!document.hidden) poll(); });
  window.addEventListener('focus', poll);
})();

// Launch: open the app with a throwaway query parameter so the request can't be answered
// from the browser's cache. The page's status can be a few seconds stale, and a cached copy
// of the app's page can load while the app is stopped, never reach the platform, and so
// never wake it (the page's own API calls then fail until you reload).
document.addEventListener('click', function (e) {
  var a = e.target.closest('[data-app-launch]');
  if (!a) return;
  if (e.ctrlKey || e.metaKey || e.shiftKey || e.button) return;     // leave modified clicks alone
  e.preventDefault();
  var url = a.getAttribute('href');
  window.open(url + (url.indexOf('?') >= 0 ? '&' : '?') + 'ign_launch=' + Date.now(), '_blank', 'noopener');
});
