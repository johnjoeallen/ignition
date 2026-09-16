// Refresh release-app state so the action button reflects the latest
// successful Start/Stop operation, even when another tab changed it.
(function () {
  var rows = document.querySelectorAll('[data-app-status-row]');
  if (!rows.length) return;

  var statusElement = document.querySelector('[data-app-status-url]');
  if (!statusElement) return;
  var url = statusElement.getAttribute('data-app-status-url');

  function setState(app) {
    var row = document.querySelector('[data-app-status-row="' + CSS.escape(app.name) + '"]');
    if (!row) return;

    var icon = row.querySelector('[data-app-status-icon]');
    if (icon) {
      icon.className = app.running
          ? 'bi bi-check-circle-fill text-success'
          : 'bi bi-x-circle-fill text-danger';
      icon.title = app.running ? 'running' : 'dead';
      icon.setAttribute('aria-label', app.running ? 'running' : 'dead');
    }

    document.querySelectorAll('[data-app-action][data-app-name="' + CSS.escape(app.name) + '"]')
      .forEach(function (form) {
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
})();
