// A full-page "please wait" overlay for form submits that kick off a slow
// server operation — e.g. app Start / Stop, which run `docker compose
// up|stop` against a node's daemon and only redirect back once that returns.
//
// Opt in per form:  <form ... data-busy="Starting app…">
// The overlay appears on submit and stays up until the response navigates
// the page (the redirect loads a fresh document, so it's gone by then).
(function () {
  function overlay(msg) {
    var el = document.createElement('div');
    el.className = 'ign-busy-overlay';
    el.innerHTML =
      '<div class="ign-busy-card">'
      + '<div class="spinner-border" role="status" aria-hidden="true"></div>'
      + '<div class="ign-busy-msg"></div>'
      + '<div class="ign-busy-sub">This can take a moment.</div>'
      + '</div>';
    el.querySelector('.ign-busy-msg').textContent = msg;
    return el;
  }

  document.addEventListener('submit', function (e) {
    var form = e.target;
    if (!(form instanceof HTMLFormElement)) return;
    var msg = form.getAttribute('data-busy');
    if (!msg) return;
    // An onsubmit="return confirm(...)" that was declined prevents the
    // default — don't pop the overlay for a submit that isn't happening.
    if (e.defaultPrevented) return;

    document.body.appendChild(overlay(msg));
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
