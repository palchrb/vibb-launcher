// Fills <i class="g" data-g="name"></i> with the glyph's SVG (glyphs.js), and the shared chrome.
document.querySelectorAll('[data-g]').forEach(e => { e.innerHTML = GLYPHS[e.dataset.g] || ''; e.classList.add('g'); });
document.querySelectorAll('.phone').forEach(p => {
  if (p.dataset.nostatus === undefined) {
    p.insertAdjacentHTML('afterbegin', `<div class="status"><span>${p.dataset.time || '16:42'}</span><span class="r"><i></i><i></i><i class="bat"></i></span></div>`);
  }
  p.insertAdjacentHTML('beforeend', '<div class="navhandle"></div>');
});
