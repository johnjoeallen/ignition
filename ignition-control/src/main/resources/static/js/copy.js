// Copy-to-clipboard buttons (.ign-copy-btn, data-copy="<value>") — used next
// to a member's own git password/PAT. Event-delegated so it works for any
// button on the page, current or added later, with no per-button wiring.
document.addEventListener('click', function (e) {
  var btn = e.target.closest('.ign-copy-btn');
  if (!btn) return;
  var text = btn.getAttribute('data-copy');
  if (!text) return;

  function copied() {
    var icon = btn.querySelector('i');
    var prev = icon.className;
    icon.className = 'bi bi-clipboard-check';
    btn.classList.add('text-success');
    setTimeout(function () {
      icon.className = prev;
      btn.classList.remove('text-success');
    }, 1500);
  }

  if (navigator.clipboard && window.isSecureContext) {
    navigator.clipboard.writeText(text).then(copied);
    return;
  }
  // Fallback for http:// (non-secure-context) access, e.g. a bare-IP demo.
  var ta = document.createElement('textarea');
  ta.value = text;
  ta.style.position = 'fixed';
  ta.style.opacity = '0';
  document.body.appendChild(ta);
  ta.focus();
  ta.select();
  try {
    document.execCommand('copy');
    copied();
  } catch (err) {
    // nothing we can do — the value is still on-screen to select by hand
  }
  document.body.removeChild(ta);
});

// Masked credentials (code.ign-masked, real value in data-value): hidden by
// default so a screenshot, screen share or over-the-shoulder glance doesn't
// show them. The eye button shows one for 30 seconds, then hides it again.
// Copy buttons are unaffected — they copy the real value either way.
(function () {
  var MASK = '\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022';
  var timers = {};

  function hide(code, btn) {
    code.textContent = MASK;
    code.removeAttribute('data-shown');
    var icon = btn && btn.querySelector('i');
    if (icon) icon.className = 'bi bi-eye';
  }

  document.addEventListener('click', function (e) {
    var btn = e.target.closest('.ign-reveal-btn');
    if (!btn) return;
    var code = document.getElementById(btn.getAttribute('data-reveal'));
    if (!code) return;
    clearTimeout(timers[code.id]);
    if (code.getAttribute('data-shown') === '1') {
      hide(code, btn);
      return;
    }
    code.textContent = code.getAttribute('data-value') || MASK;
    code.setAttribute('data-shown', '1');
    btn.querySelector('i').className = 'bi bi-eye-slash';
    timers[code.id] = setTimeout(function () { hide(code, btn); }, 30000);
  });
})();
