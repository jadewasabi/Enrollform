// scroll reveal
const io = new IntersectionObserver(es => es.forEach(e => {
  if (e.isIntersecting) { e.target.classList.add('in'); io.unobserve(e.target); }
}), { threshold: .15 });
document.querySelectorAll('.rv').forEach(el => io.observe(el));

// strands carousel dots
const track = document.getElementById('track'), dots = document.getElementById('dots');
if (track && dots) {
  const cards = [...track.children];
  cards.forEach((_, i) => {
    const b = document.createElement('button');
    b.setAttribute('aria-label', 'Strand ' + (i + 1));
    b.onclick = () => track.scrollTo({ left: cards[i].offsetLeft - track.offsetLeft, behavior: 'smooth' });
    dots.appendChild(b);
  });
  const setDot = () => {
    let idx = 0, min = 1e9;
    cards.forEach((c, k) => {
      const d = Math.abs(c.offsetLeft - track.offsetLeft - track.scrollLeft);
      if (d < min) { min = d; idx = k; }
    });
    [...dots.children].forEach((d, k) => d.classList.toggle('on', k === idx));
  };
  track.addEventListener('scroll', setDot, { passive: true });
  setDot();
}

// word-by-word highlight on scroll
const h = document.getElementById('reveal-text');
if (h) {
  h.innerHTML = h.textContent.split(' ').map(w => '<span>' + w + '</span>').join(' ');
  const words = [...h.children];
  const paint = () => {
    const r = h.getBoundingClientRect();
    const p = Math.min(1, Math.max(0, (innerHeight * .85 - r.top) / (innerHeight * .5)));
    words.forEach((w, i) => w.style.color = i / words.length < p ? 'var(--ink)' : '');
  };
  addEventListener('scroll', paint, { passive: true });
  paint();
}

// count-up stats
const co = new IntersectionObserver(es => es.forEach(e => {
  if (!e.isIntersecting) return;
  co.unobserve(e.target);
  const n = +e.target.dataset.n, s = e.target.dataset.s, t0 = performance.now();
  const f = t => {
    const k = Math.min(1, (t - t0) / 1400), v = Math.round(n * (1 - Math.pow(1 - k, 3)));
    e.target.textContent = (n >= 1000 ? (v / 1000).toFixed(v >= n ? 1 : 2).replace(/\.0$/, '') + 'K' : v) + s;
    if (k < 1) requestAnimationFrame(f);
  };
  requestAnimationFrame(f);
}), { threshold: .6 });
document.querySelectorAll('[data-n]').forEach(el => co.observe(el));


// contact form: opens the visitor's email app (Gmail) with the message already written
const SCHOOL_EMAIL = 'jadeamber485@gmail.com';   // <-- CHANGE THIS to your school's Gmail address
const form = document.getElementById('contactForm');
if (form) form.addEventListener('submit', e => {
  e.preventDefault();
  const d = new FormData(form);
  const subject = 'Inquiry from ' + d.get('name') + ' (' + d.get('strand') + ')';
  const body = 'Name: ' + d.get('name') + '\nEmail: ' + d.get('email') +
               '\nStrand of interest: ' + d.get('strand') + '\n\n' + d.get('message');
  const q = 'subject=' + encodeURIComponent(subject) + '&body=' + encodeURIComponent(body);
  const phone = /Android|iPhone|iPad|iPod/i.test(navigator.userAgent);
  // phone: opens the mail app (Gmail if it is the default). computer: opens Gmail in a new tab
  if (phone) location.href = 'mailto:' + SCHOOL_EMAIL + '?' + q;
  else window.open('https://mail.google.com/mail/?view=cm&fs=1&to=' + encodeURIComponent(SCHOOL_EMAIL) + '&' + q, '_blank');
  form.querySelector('.ok').style.display = 'block';
});

// preselect strand on the enroll link: enroll.html?strand=STEM
document.querySelectorAll('[data-strand]').forEach(a => {
  a.href = 'enroll.html?strand=' + encodeURIComponent(a.dataset.strand);
});

// ===== photos from images.js =====
const IMG = (typeof SITE_IMAGES !== 'undefined') ? SITE_IMAGES : {};
const loadImg = src => new Promise((ok, fail) => {
  const i = new Image(); i.onload = () => ok(src); i.onerror = () => fail(src); i.src = src;
});
const warn = s => console.warn('[images.js] Could not load image: ' + s + ' (check the file name and folder)');
const withImg = (el, src, overlay, done) => {
  if (!el || !src) return;
  loadImg(src).then(() => {
    el.style.backgroundImage = (overlay ? overlay + ',' : '') + 'url("' + src + '")';
    el.style.backgroundSize = 'cover'; el.style.backgroundPosition = 'center';
    el.classList.add('has-img'); if (done) done();
  }).catch(warn);
};

// hero banner
const dk = (IMG.heroDarkness ?? .5);
withImg(document.querySelector('.hero'), IMG.hero,
  'linear-gradient(160deg,rgba(0,0,0,' + dk * .8 + '),rgba(0,0,0,' + Math.min(1, dk * 1.4) + '))');

// tag thumbnail, principal, video poster
withImg(document.getElementById('tagImg'), IMG.tag);
withImg(document.getElementById('principalImg'), IMG.principal);
withImg(document.getElementById('videoBox'), IMG.videoPoster);

// strand cards
document.querySelectorAll('.card [data-strand]').forEach(btn => {
  const card = btn.closest('.card'), src = IMG.strands && IMG.strands[btn.dataset.strand];
  withImg(card, src, 'linear-gradient(160deg,rgba(0,0,0,.15),rgba(0,0,0,.8))');
});

// video: play real file if given
const vbox = document.getElementById('videoBox'), pbtn = document.getElementById('play');
if (vbox && pbtn) pbtn.onclick = () => {
  if (!IMG.videoFile) return;
  const v = document.createElement('video');
  v.src = IMG.videoFile; v.controls = true; v.autoplay = true; v.playsInline = true;
  vbox.appendChild(v); pbtn.style.display = 'none';
};

// campus carousel (built from images.js; the list is added twice for a seamless loop)
const mq = document.getElementById('marquee');
if (mq) {
  const grads = ['#9a5d25,#2d1a0a','#3b4a5e,#0b0f16','#3f6b4f,#0b1a11','#7a3b4e,#1a0b11','#5a4a8a,#120d1f','#8a6a25,#1f160a'];
  const list = IMG.campus || [];
  const makeShot = (p, i, copy) => {
    const fig = document.createElement('figure'); fig.className = 'shot';
    if (copy) fig.setAttribute('aria-hidden', 'true');
    const ph = document.createElement('div'); ph.className = 'ph';
    ph.style.background = 'linear-gradient(160deg,' + grads[i % grads.length] + ')';
    fig.appendChild(ph);
    if (p.src) {
      const im = document.createElement('img');
      im.alt = p.caption || '';
      im.onload = () => ph.remove();                       // photo loaded: hide the colored placeholder
      im.onerror = () => { im.remove(); if (!copy) warn(p.src); };
      im.src = p.src;
      fig.appendChild(im);
    }
    const cap = document.createElement('figcaption'); cap.textContent = p.caption || '';
    fig.appendChild(cap);
    return fig;
  };
  [false, true].forEach(copy => list.forEach((p, i) => mq.appendChild(makeShot(p, i, copy))));
}