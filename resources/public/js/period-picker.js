/*
 * period-picker.js — vanilla JS behaviour for the global period picker.
 *
 * The server emits a `period-picker` shell (components/period-picker); this
 * file wires up:
 *
 *   - Initial state from URL params (?from=…&to=…) → localStorage → default
 *     last-30-days.
 *   - Chip text hydration on DOMContentLoaded (so URL state wins over the
 *     server-rendered default).
 *   - Popover toggle (window.togglePeriodPicker / closePeriodPicker).
 *   - Calendar grid rendered inside #period-picker-calendar with:
 *       · selected-range highlight (from / to / in-between)
 *       · green/gray coverage bars (fetched from /api/coverage)
 *       · month nav (◀ / ▶)
 *       · click-to-set-endpoint (1st click = from, 2nd = to; out-of-order swaps)
 *   - Preset buttons (7 дней / 30 дней / Этот месяц / Пред. месяц / Custom).
 *   - Compare-toggle (state.compare = 'prev' | 'none').
 *   - Apply button → writes localStorage + updates URL (keeps other search
 *     params) and reloads the page with the new period.
 *
 * All dates are UTC-normalised so day-of-month math never drifts by TZ.
 */
(function() {
  'use strict';

  // ---------- propagate-on-navigate ----------
  // If the URL has no ?from&to but localStorage has a saved period,
  // redirect *before* the rest of the page renders. Without this, every
  // sidebar nav click resets to the server default and the user has to
  // re-apply the picker on each page.
  (function syncUrlFromStorage() {
    const q = new URLSearchParams(window.location.search);
    if (q.get('from') && q.get('to')) return; // URL already authoritative
    let saved;
    try { saved = JSON.parse(localStorage.getItem('analitica/period') || 'null'); }
    catch (_) { return; }
    if (!saved || !saved.from || !saved.to) return;
    const url = new URL(window.location);
    url.searchParams.set('from', saved.from);
    url.searchParams.set('to', saved.to);
    if (saved.compare && saved.compare !== 'none') {
      url.searchParams.set('compare', saved.compare);
    }
    // location.replace cancels the current load — no content flash.
    window.location.replace(url.toString());
  })();

  // ---------- date helpers (UTC-safe) ----------
  function fmtISO(d) {
    const y = d.getUTCFullYear();
    const m = String(d.getUTCMonth() + 1).padStart(2, '0');
    const day = String(d.getUTCDate()).padStart(2, '0');
    return y + '-' + m + '-' + day;
  }

  function parseISO(s) {
    // Parse as UTC-midnight to avoid TZ drift.
    const [y, m, d] = s.split('-').map(Number);
    return new Date(Date.UTC(y, m - 1, d));
  }

  function addDays(d, n) {
    const r = new Date(d);
    r.setUTCDate(r.getUTCDate() + n);
    return r;
  }

  function isoToday() {
    const t = new Date();
    return fmtISO(new Date(Date.UTC(t.getFullYear(), t.getMonth(), t.getDate())));
  }

  function fmtRuDate(iso) {
    if (!iso) return '';
    const [y, m, d] = iso.split('-');
    return d + '.' + m + '.' + y;
  }

  function capitalize(s) {
    return s && s.charAt(0).toUpperCase() + s.slice(1);
  }

  function resolvePreset(preset) {
    const todayISO = isoToday();
    const today = parseISO(todayISO);
    switch (preset) {
      case 'last-7-days':  return [fmtISO(addDays(today, -6)), todayISO];
      case 'last-30-days': return [fmtISO(addDays(today, -29)), todayISO];
      case 'this-month': {
        const firstDay = new Date(Date.UTC(today.getUTCFullYear(), today.getUTCMonth(), 1));
        return [fmtISO(firstDay), todayISO];
      }
      case 'prev-month': {
        // First of prev month → last of prev month. Day=0 of current month
        // gives the last day of the previous month (rolls year on Jan).
        const firstDay = new Date(Date.UTC(today.getUTCFullYear(), today.getUTCMonth() - 1, 1));
        const lastDay  = new Date(Date.UTC(today.getUTCFullYear(), today.getUTCMonth(), 0));
        return [fmtISO(firstDay), fmtISO(lastDay)];
      }
      default: return null; // :custom — caller ignores
    }
  }

  // ---------- state ----------
  const state = {
    from: null,
    to: null,
    compare: 'none',
    coverage: new Set(), // ISO dates with at least one finance row
    displayMonth: null,  // Date (UTC) pinned to the 1st of the month shown
    firstClickDone: false
  };

  function loadInitial() {
    const q = new URLSearchParams(window.location.search);
    if (q.get('from') && q.get('to')) {
      state.from = q.get('from');
      state.to = q.get('to');
      state.compare = q.get('compare') || 'none';
      return;
    }
    const saved = localStorage.getItem('analitica/period');
    if (saved) {
      try {
        const p = JSON.parse(saved);
        if (p.from && p.to) {
          state.from = p.from;
          state.to = p.to;
          state.compare = p.compare || 'none';
          return;
        }
      } catch (_) { /* fall through to default */ }
    }
    const [f, t] = resolvePreset('last-30-days');
    state.from = f;
    state.to = t;
  }

  function hydrateChipText() {
    const trigger = document.getElementById('period-picker-trigger');
    if (!trigger) return;
    const span = trigger.querySelector('span.font-semibold');
    if (span) span.textContent = fmtRuDate(state.from) + ' — ' + fmtRuDate(state.to);
  }

  // ---------- coverage fetch ----------
  async function fetchCoverage() {
    try {
      const url = '/api/coverage?from=' + encodeURIComponent(state.from)
                + '&to=' + encodeURIComponent(state.to);
      const r = await fetch(url);
      if (!r.ok) { state.coverage = new Set(); return; }
      const data = await r.json();
      state.coverage = new Set(data.days || []);
    } catch (_) {
      state.coverage = new Set();
    }
  }

  // ---------- calendar render ----------
  function renderCalendar() {
    const container = document.getElementById('period-picker-calendar');
    if (!container) return;

    // Keep chip text in sync with state on every re-render.
    hydrateChipText();

    // Anchor the display month on state.from when not already pinned.
    if (!state.displayMonth) state.displayMonth = parseISO(state.from);
    const dm = state.displayMonth;
    const monthLabel = dm.toLocaleDateString('ru-RU', {
      month: 'long', year: 'numeric', timeZone: 'UTC'
    });

    const firstOfMonth = new Date(Date.UTC(dm.getUTCFullYear(), dm.getUTCMonth(), 1));
    // Monday-first week: JS getUTCDay() is 0=Sun…6=Sat; shift so Mon=0.
    const startWeekday = (firstOfMonth.getUTCDay() + 6) % 7;
    const daysInMonth  = new Date(Date.UTC(dm.getUTCFullYear(), dm.getUTCMonth() + 1, 0)).getUTCDate();

    let html = '<div class="flex justify-between items-center mb-2">'
             + '  <button type="button" class="px-2 py-0.5 text-sm hover:bg-gray-100 rounded" id="picker-prev-month">◀</button>'
             + '  <div class="text-sm font-semibold text-gray-700">' + capitalize(monthLabel) + '</div>'
             + '  <button type="button" class="px-2 py-0.5 text-sm hover:bg-gray-100 rounded" id="picker-next-month">▶</button>'
             + '</div>';

    html += '<div class="grid grid-cols-7 gap-0.5 text-xs">';
    ['П','В','С','Ч','П','С','В'].forEach(function(w) {
      html += '<div class="text-center text-gray-400 py-1">' + w + '</div>';
    });
    for (let i = 0; i < startWeekday; i++) html += '<div></div>';

    for (let d = 1; d <= daysInMonth; d++) {
      const date   = new Date(Date.UTC(dm.getUTCFullYear(), dm.getUTCMonth(), d));
      const iso    = fmtISO(date);
      const inRange = iso >= state.from && iso <= state.to;
      const isStart = iso === state.from;
      const isEnd   = iso === state.to;
      const hasData = state.coverage.has(iso);

      let cls = 'text-center p-1 rounded cursor-pointer select-none';
      if (isStart || isEnd) cls += ' bg-blue-600 text-white';
      else if (inRange)     cls += ' bg-blue-100 text-blue-900';
      else                  cls += ' hover:bg-gray-100';

      const barCls = hasData ? 'bg-green-500' : 'bg-gray-200';
      html += '<div class="' + cls + '" data-date="' + iso + '">'
            +   d
            +   '<div class="h-0.5 mt-0.5 ' + barCls + ' rounded"></div>'
            + '</div>';
    }
    html += '</div>';

    // Legend
    html += '<div class="text-xs text-gray-500 mt-2 flex gap-3">'
          + '  <span><span class="inline-block w-3 h-0.5 bg-green-500 align-middle"></span> с данными</span>'
          + '  <span><span class="inline-block w-3 h-0.5 bg-gray-200 align-middle"></span> без</span>'
          + '</div>';

    container.innerHTML = html;

    // Day clicks → update range endpoints
    container.querySelectorAll('[data-date]').forEach(function(el) {
      el.addEventListener('click', function() {
        const d = el.dataset.date;
        if (!state.firstClickDone) {
          state.from = d;
          state.to = d;
          state.firstClickDone = true;
        } else {
          if (d < state.from) {
            state.to = state.from;
            state.from = d;
          } else {
            state.to = d;
          }
          state.firstClickDone = false;
          // New range → refresh coverage for the new window
          fetchCoverage().then(function() {
            renderCalendar();
            updateSummary();
          });
          return;
        }
        renderCalendar();
        updateSummary();
      });
    });

    // Month nav
    const prev = document.getElementById('picker-prev-month');
    const next = document.getElementById('picker-next-month');
    if (prev) prev.addEventListener('click', function() {
      state.displayMonth = new Date(Date.UTC(dm.getUTCFullYear(), dm.getUTCMonth() - 1, 1));
      renderCalendar();
    });
    if (next) next.addEventListener('click', function() {
      state.displayMonth = new Date(Date.UTC(dm.getUTCFullYear(), dm.getUTCMonth() + 1, 1));
      renderCalendar();
    });
  }

  function updateSummary() {
    const el = document.getElementById('period-picker-summary');
    if (!el) return;
    const n = Math.round((parseISO(state.to) - parseISO(state.from)) / 86400000) + 1;
    let withData = 0;
    state.coverage.forEach(function(d) {
      if (d >= state.from && d <= state.to) withData++;
    });
    el.textContent = n + ' дней · ' + withData + ' с данными · ' + (n - withData) + ' без';
  }

  // ---------- popover toggle ----------
  window.togglePeriodPicker = function() {
    const p = document.getElementById('period-picker-popover');
    if (!p) return;
    if (p.style.display === 'none' || p.style.display === '') {
      p.style.display = 'block';
      fetchCoverage().then(function() {
        state.displayMonth = null; // re-anchor on current state.from
        renderCalendar();
        updateSummary();
      });
    } else {
      p.style.display = 'none';
    }
  };

  window.closePeriodPicker = function() {
    const p = document.getElementById('period-picker-popover');
    if (p) p.style.display = 'none';
  };

  // ---------- setup on DOM ready ----------
  document.addEventListener('DOMContentLoaded', function() {
    loadInitial();
    hydrateChipText();

    // Preset buttons
    document.querySelectorAll('.preset-option').forEach(function(btn) {
      btn.addEventListener('click', function() {
        const preset = btn.dataset.preset;
        if (preset === 'custom') return; // :custom is a no-op; user picks days manually
        const range = resolvePreset(preset);
        if (!range) return;
        state.from = range[0];
        state.to = range[1];
        state.displayMonth = null;
        state.firstClickDone = false;
        fetchCoverage().then(function() {
          renderCalendar();
          updateSummary();
        });
      });
    });

    // Compare toggle
    const cmp = document.getElementById('compare-toggle');
    if (cmp) {
      cmp.checked = state.compare === 'prev';
      cmp.addEventListener('change', function() {
        state.compare = cmp.checked ? 'prev' : 'none';
      });
    }

    // Apply → persist + navigate (full reload keeps server-rendered tables in sync)
    const apply = document.getElementById('period-picker-apply');
    if (apply) {
      apply.addEventListener('click', function() {
        localStorage.setItem('analitica/period', JSON.stringify({
          from: state.from, to: state.to, compare: state.compare
        }));
        const url = new URL(window.location);
        url.searchParams.set('from', state.from);
        url.searchParams.set('to', state.to);
        url.searchParams.delete('period'); // legacy preset-name param
        if (state.compare === 'prev') url.searchParams.set('compare', 'prev');
        else url.searchParams.delete('compare');
        window.location.href = url.toString();
      });
    }
  });
})();
