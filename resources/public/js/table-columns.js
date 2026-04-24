(function() {
  // Tabulator instance lookup: components.clj sets window['<tableId>_tabulator']
  function getTable(tableId) {
    return window[tableId + '_tabulator'] || null;
  }

  window.applyPreset = function(tableId, presetKey) {
    const table = getTable(tableId);
    if (!table) { console.warn('applyPreset: table not ready', tableId); return; }
    const presetRegistry = window[tableId + '_presets'] || {};
    const preset = presetRegistry[presetKey];
    if (preset === undefined) { console.warn('applyPreset: unknown preset', presetKey); return; }
    const allFields = table.getColumns(true /* include groups */).flatMap(function getFields(c) {
      const sub = c.getSubColumns ? c.getSubColumns() : [];
      return sub.length ? sub.flatMap(getFields) : [c.getField()];
    }).filter(Boolean);
    const visibleSet =
      (preset === 'all' || preset === ':all')
        ? new Set(allFields)
        : (preset === 'all-default-visible' || preset === ':all-default-visible')
          ? new Set(window[tableId + '_defaultVisible'] || allFields)
          : new Set(preset);
    allFields.forEach(function(field) {
      if (visibleSet.has(field)) table.showColumn(field);
      else table.hideColumn(field);
    });
    // Update chip active state
    document.querySelectorAll('[data-preset][data-table-id="' + tableId + '"]').forEach(function(chip) {
      const isActive = chip.dataset.preset === presetKey;
      chip.classList.toggle('preset-chip-active', isActive);
      chip.classList.toggle('bg-blue-600', isActive);
      chip.classList.toggle('text-white', isActive);
      chip.classList.toggle('bg-white', !isActive);
      chip.classList.toggle('text-gray-700', !isActive);
    });
    // Sync chooser checkbox state
    document.querySelectorAll('input[type="checkbox"][data-table-id="' + tableId + '"]').forEach(function(cb) {
      cb.checked = visibleSet.has(cb.dataset.colKey);
    });
    localStorage.setItem('analitica/preset/' + tableId, presetKey);
  };

  window.toggleColumn = function(tableId, field, visible) {
    const table = getTable(tableId);
    if (!table) { console.warn('toggleColumn: table not ready', tableId); return; }
    if (visible) table.showColumn(field); else table.hideColumn(field);
    // User made a manual change — no preset is "active" anymore
    document.querySelectorAll('[data-preset][data-table-id="' + tableId + '"].preset-chip-active').forEach(function(chip) {
      chip.classList.remove('preset-chip-active', 'bg-blue-600', 'text-white');
      chip.classList.add('bg-white', 'text-gray-700');
    });
    localStorage.setItem('analitica/preset/' + tableId, 'custom');
  };

  // Restore last preset on page load — wait briefly for tabulator to instantiate
  document.addEventListener('DOMContentLoaded', function() {
    setTimeout(function() {
      Object.keys(window).filter(function(k) { return k.endsWith('_presets'); }).forEach(function(k) {
        const tableId = k.replace(/_presets$/, '');
        const saved = localStorage.getItem('analitica/preset/' + tableId);
        if (saved && saved !== 'custom' && window[tableId + '_presets'][saved] !== undefined) {
          window.applyPreset(tableId, saved);
        }
      });
    }, 500);
  });
})();
