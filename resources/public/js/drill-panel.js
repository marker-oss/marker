(function() {
  function formatRub(v) {
    if (v == null) return '—';
    return new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 0 }).format(v) + ' ₽';
  }
  function formatPct(v) {
    if (v == null) return '—';
    return v.toFixed(1) + '%';
  }
  function formatInt(v) {
    if (v == null) return '—';
    return new Intl.NumberFormat('ru-RU').format(v);
  }

  function formatMetric(key, value) {
    if (typeof value === 'boolean') return String(value);
    if (typeof value !== 'number') return String(value ?? '—');
    if (key.includes('pct') || key.includes('rate')) return formatPct(value);
    if (Math.abs(value) >= 100 || key.includes('revenue') || key.includes('profit') || key.includes('cost') || key.includes('reward') || key.includes('logistics') || key.includes('storage') || key.includes('spend') || key.includes('for-pay')) return formatRub(value);
    return formatInt(value);
  }

  function humanLabel(key) {
    return key.replace(/-/g, ' ');
  }

  window.openDrillPanel = function(reportType, article, period, marketplace) {
    const panel = document.getElementById('drill-panel');
    const titleEl = document.getElementById('drill-panel-title');
    const contentEl = document.getElementById('drill-panel-content');
    if (!panel || !titleEl || !contentEl) return;

    panel.style.display = '';
    requestAnimationFrame(function() { panel.classList.remove('translate-x-full'); });
    titleEl.textContent = article;
    contentEl.innerHTML = '<div class="text-sm text-gray-500">Загрузка…</div>';

    const mp = marketplace && marketplace !== 'all' ? '&marketplace=' + marketplace : '';
    const url = '/api/report/' + reportType + '/article/' + encodeURIComponent(article) + '?period=' + encodeURIComponent(period) + mp;

    fetch(url)
      .then(function(r) { return r.json(); })
      .then(function(data) {
        const kpi = data.kpi || {};
        const breakdown = data.breakdown || {};

        let html = '';
        // KPI grid (2 cols)
        if (Object.keys(kpi).length) {
          html += '<div class="grid grid-cols-2 gap-2 mb-3">';
          Object.entries(kpi).forEach(function(entry) {
            const k = entry[0], v = entry[1];
            html += '<div class="bg-gray-100 p-2 rounded text-xs">'
                  + '<div class="text-gray-500">' + humanLabel(k) + '</div>'
                  + '<div class="font-bold text-sm">' + formatMetric(k, v) + '</div>'
                  + '</div>';
          });
          html += '</div>';
        }

        // Breakdown
        if (Object.keys(breakdown).length) {
          html += '<div class="text-xs font-semibold text-gray-600 mb-1">Где утечка маржи</div>';
          html += '<div class="text-xs space-y-0">';
          Object.entries(breakdown).forEach(function(entry) {
            const k = entry[0], v = entry[1];
            html += '<div class="flex justify-between border-b border-gray-100 py-1">'
                  + '<span class="text-gray-600 font-mono">' + humanLabel(k) + '</span>'
                  + '<span class="font-semibold">' + formatMetric(k, v) + '</span>'
                  + '</div>';
          });
          html += '</div>';
        }

        if (!Object.keys(kpi).length && !Object.keys(breakdown).length) {
          html = '<div class="text-sm text-gray-500">Нет данных по артикулу за выбранный период.</div>';
        }

        contentEl.innerHTML = html;
      })
      .catch(function(err) {
        contentEl.innerHTML = '<div class="text-sm text-red-600">Ошибка загрузки: ' + (err && err.message || err) + '</div>';
      });
  };

  window.closeDrillPanel = function() {
    const panel = document.getElementById('drill-panel');
    if (!panel) return;
    panel.classList.add('translate-x-full');
    setTimeout(function() { panel.style.display = 'none'; }, 200);
  };
})();
