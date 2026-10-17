// Regenerate-password / regenerate-PAT buttons: do it in the background and
// swap the new value into the page, instead of a redirect (which would carry
// the secret in a URL). Without JS the form posts normally; if the background
// request fails, a "failed" note is shown and the button can be pressed again.
(function () {
  var RESET = /\/members\/reset-(git-password|pat)$/;

  document.addEventListener('submit', function (e) {
    var form = e.target;
    if (!(form instanceof HTMLFormElement) || !window.fetch) return;
    if (!RESET.test(new URL(form.action, window.location.href).pathname)) return;
    if (e.defaultPrevented) return; // the confirm() was declined
    e.preventDefault();

    var holder = form.parentElement;
    var btn = holder.querySelector('.ign-copy-btn');
    var old = btn ? btn.getAttribute('data-copy') : null;
    var submit = form.querySelector('button');
    if (submit) submit.disabled = true;

    function note(text, ok) {
      var el = document.createElement('span');
      el.className = 'small ms-1 ' + (ok ? 'text-success' : 'text-danger');
      el.textContent = text;
      form.after(el);
      setTimeout(function () { el.remove(); }, 4000);
    }

    fetch(form.action, {
      method: 'POST',
      body: new URLSearchParams(new FormData(form)),
      headers: { 'Accept': 'application/json' },
      credentials: 'same-origin'
    }).then(function (r) {
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.json();
    }).then(function (j) {
      if (j.value && old) {
        document.querySelectorAll('code').forEach(function (c) {
          if (c.textContent === old) c.textContent = j.value;
        });
        document.querySelectorAll('[data-copy]').forEach(function (b) {
          if (b.getAttribute('data-copy') === old) b.setAttribute('data-copy', j.value);
        });
      }
      note('regenerated', true);
    }).catch(function (err) {
      note('failed: ' + err.message, false);
    }).then(function () {
      if (submit) submit.disabled = false;
    });
  });
})();
