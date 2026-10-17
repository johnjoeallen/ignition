// A full-page "please wait" overlay for form submits that kick off a slow
// server operation — e.g. app Start / Stop, which run `docker compose
// up|stop` against a node's daemon and only redirect back once that returns.
//
// Opt in per form:  <form ... data-busy="Starting app…">
// The overlay appears on submit and stays up until the response navigates
// the page (the redirect loads a fresh document, so it's gone by then).
//
// An app Start form (data-app-action="start") is submitted in the background
// instead: `compose start` returns as soon as the containers exist, long
// before the app answers, so the overlay then keeps polling the app status
// (which includes the health probe on its public URL) and only navigates once
// the app is up — or after 3 minutes, or when "Stop waiting" is pressed.
(function () {
  function overlay(msg) {
    var el = document.createElement('div');
    el.className = 'ign-busy-overlay';
    el.innerHTML =
      '<div class="ign-busy-card">'
      + '<div class="spinner-border" role="status" aria-hidden="true"></div>'
      + '<div class="ign-busy-msg"></div>'
      + '<div class="ign-busy-sub">This can take a moment.</div>'
      + '<button type="button" class="btn btn-sm btn-outline-secondary ign-busy-skip" hidden>Stop waiting</button>'
      + '</div>';
    el.querySelector('.ign-busy-msg').textContent = msg;
    return el;
  }

  function startAndWait(form, el, statusUrl) {
    var name = form.getAttribute('data-app-name');
    var channel = form.getAttribute('data-app-channel') || 'release';
    var sub = el.querySelector('.ign-busy-sub');
    var skip = el.querySelector('.ign-busy-skip');
    var target = window.location.href;
    var finished = false;
    function done() {
      if (finished) return;
      finished = true;
      window.location.href = target;
    }
    skip.hidden = false;
    skip.addEventListener('click', done);

    form.querySelectorAll('button, input[type=submit]').forEach(function (b) { b.disabled = true; });
    fetch(form.action, {
      method: 'POST',
      body: new URLSearchParams(new FormData(form)),
      credentials: 'same-origin'
    }).then(function (res) {
      target = res.url || target; // the page the server redirected to, with its message
      sub.textContent = 'Containers started — waiting for ' + name + ' to answer…';
      var deadline = Date.now() + 180000;
      (function tick() {
        if (finished) return;
        fetch(statusUrl, { headers: { 'Accept': 'application/json' }, credentials: 'same-origin' })
          .then(function (r) { return r.json(); })
          .then(function (list) {
            var a = list.filter(function (x) { return x.name === name && x.channel === channel; })[0];
            if ((a && a.running) || Date.now() > deadline) done(); else setTimeout(tick, 2000);
          })
          .catch(function () { if (Date.now() > deadline) done(); else setTimeout(tick, 2000); });
      })();
    }).catch(done);
  }

  document.addEventListener('submit', function (e) {
    var form = e.target;
    if (!(form instanceof HTMLFormElement)) return;
    var msg = form.getAttribute('data-busy');
    if (!msg) return;
    // An onsubmit="return confirm(...)" that was declined prevents the
    // default — don't pop the overlay for a submit that isn't happening.
    if (e.defaultPrevented) return;

    var el = overlay(msg);
    document.body.appendChild(el);
    var statusEl = document.querySelector('[data-app-status-url]');
    if (form.getAttribute('data-app-action') === 'start' && statusEl && window.fetch) {
      e.preventDefault();
      startAndWait(form, el, statusEl.getAttribute('data-app-status-url'));
      return;
    }
    // Belt and braces against a double-submit while we wait.
    form.querySelectorAll('button, input[type=submit]').forEach(function (b) {
      b.disabled = true;
    });
  });

  // If the user hits Back onto a bfcache'd copy of this page, drop any
  // overlay that got restored with the DOM.
  window.addEventListener('pageshow', function (e) {
    if (!e.persisted) return;
    document.querySelectorAll('.ign-busy-overlay').forEach(function (el) { el.remove(); });
  });
})();
