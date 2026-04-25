/**
 * sku-sheet.js — SKU drill-down side panel.
 *
 * Click delegation: any [data-sku] or [data-nm-id] button anywhere on the
 * page fetches /api/sku/:id and renders the result into the #sku-sheet dialog.
 *
 * The dialog element is expected in layout.clj:
 *   <dialog id="sku-sheet" class="sku-sheet">
 *     <div class="sku-sheet-content">Загрузка…</div>
 *     <button class="sku-sheet-close" onclick="this.closest('dialog').close()">×</button>
 *   </dialog>
 */
(function () {
  'use strict';

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  function urlParam(name) {
    return new URLSearchParams(location.search).get(name) || '';
  }

  function defaultFrom() {
    const d = new Date();
    d.setDate(d.getDate() - 29);
    return d.toISOString().slice(0, 10);
  }

  function defaultTo() {
    return new Date().toISOString().slice(0, 10);
  }

  function buildApiUrl(identifier) {
    const from = urlParam('from') || defaultFrom();
    const to   = urlParam('to')   || defaultTo();
    const mp   = urlParam('marketplace') || '';
    const base = '/api/sku/' + encodeURIComponent(identifier);
    const qs   = new URLSearchParams({ from, to });
    if (mp) qs.set('marketplace', mp);
    return base + '?' + qs.toString();
  }

  // -------------------------------------------------------------------------
  // Dialog setup
  // -------------------------------------------------------------------------

  const dialog = document.getElementById('sku-sheet');

  if (!dialog) {
    // layout.clj hasn't included the dialog element yet — bail silently.
    return;
  }

  const contentEl = dialog.querySelector('.sku-sheet-content');

  // Close on click outside the dialog box (click on the backdrop)
  dialog.addEventListener('click', function (e) {
    if (e.target === dialog) {
      dialog.close();
    }
  });

  // ESC is handled natively by <dialog>.

  // -------------------------------------------------------------------------
  // Click delegation — handles Tabulator sku-link buttons
  // -------------------------------------------------------------------------

  document.addEventListener('click', async function (e) {
    const btn = e.target.closest('[data-sku],[data-nm-id]');
    if (!btn) return;

    // Prefer nm-id as the canonical identifier; fall back to article.
    const nmId  = btn.dataset.nmId  || '';
    const sku   = btn.dataset.sku   || '';
    const id    = nmId || sku;

    if (!id) return;

    e.preventDefault();
    e.stopPropagation();

    // Show dialog with loading state
    if (contentEl) contentEl.innerHTML = '<div class="sku-sheet-loading">Загрузка…</div>';
    dialog.showModal();

    try {
      const url  = buildApiUrl(id);
      const resp = await fetch(url);

      if (!resp.ok) {
        if (contentEl) {
          contentEl.innerHTML = '<div class="sku-sheet-error">Ошибка загрузки (' + resp.status + ')</div>';
        }
        return;
      }

      const html = await resp.text();
      if (contentEl) contentEl.innerHTML = html;
    } catch (err) {
      console.error('[sku-sheet] fetch error:', err);
      if (contentEl) {
        contentEl.innerHTML = '<div class="sku-sheet-error">Ошибка сети</div>';
      }
    }
  });

})();
