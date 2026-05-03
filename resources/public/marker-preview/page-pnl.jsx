// P&L Report page with bulk actions
function PnL({ compare, mpFilter, density, onOpenSku }) {
  const D = window.MARKER_DATA;
  const F = window.MarkerFmt;
  const [selected, setSelected] = useStateP(new Set());
  const [filter, setFilter] = useStateP('');

  const visible = useMemoP(() => {
    return D.SKUS.filter(s => {
      if (mpFilter.length > 0 && !s.mp.some(m => mpFilter.includes(m))) return false;
      if (filter && !(s.id.toLowerCase().includes(filter.toLowerCase()) || s.name.toLowerCase().includes(filter.toLowerCase()))) return false;
      return true;
    });
  }, [mpFilter, filter]);

  const allSelected = visible.length > 0 && visible.every(s => selected.has(s.id));
  const someSelected = visible.some(s => selected.has(s.id));

  const toggleAll = () => {
    const next = new Set(selected);
    if (allSelected) visible.forEach(s => next.delete(s.id));
    else visible.forEach(s => next.add(s.id));
    setSelected(next);
  };
  const toggleOne = (id) => {
    const next = new Set(selected);
    next.has(id) ? next.delete(id) : next.add(id);
    setSelected(next);
  };

  return (
    <>
      {selected.size > 0 && (
        <div className="bulk-bar">
          <strong>Выбрано {selected.size} {F.pluralRu(selected.size, 'строка', 'строки', 'строк')}</strong>
          <button className="btn-link" style={{ color: '#cbd5e1' }} onClick={() => setSelected(new Set())}>Снять</button>
          <div className="spacer" />
          <button className="btn btn-secondary btn-sm">
            Действие <MarkerIcon name="chevDown" size={12} />
          </button>
          <button className="btn btn-secondary btn-sm">
            <MarkerIcon name="download" size={14} /> Export
          </button>
          <button className="icon-btn" style={{ color: '#cbd5e1' }} onClick={() => setSelected(new Set())}>
            <MarkerIcon name="x" size={14} />
          </button>
        </div>
      )}

      {/* P&L summary */}
      <section className="card section-card">
        <div className="section-head">
          <div>
            <h3 className="section-title">P&L · Май 2026</h3>
            <div className="section-subtitle">{compare ? 'сравнение с апрелем 2026' : 'без сравнения'}</div>
          </div>
          <div className="row">
            <button className="btn btn-secondary btn-sm"><MarkerIcon name="download" size={14}/>Export</button>
            <button className="icon-btn"><MarkerIcon name="moreH"/></button>
          </div>
        </div>
        <table className="tbl">
          <thead>
            <tr>
              <th>Статья</th>
              <th className="num">Май 2026</th>
              {compare && <th className="num">Апр 2026</th>}
              {compare && <th className="num">Δ ₽</th>}
              {compare && <th className="num">Δ %</th>}
              <th className="num">% от выручки</th>
            </tr>
          </thead>
          <tbody>
            {D.PNL_ROWS.map(r => {
              const delta = r.cur - r.prev;
              const deltaPct = (delta / Math.abs(r.prev)) * 100;
              const isSubtotal = r.group === 'subtotal' || r.group === 'total';
              const revenue = D.PNL_ROWS[0].cur;
              const pctRev = (Math.abs(r.cur) / revenue) * 100;
              const isCost = r.cur < 0;
              return (
                <tr key={r.key} style={isSubtotal ? { background: 'var(--color-bg-subtle)', fontWeight: 600 } : {}}>
                  <td style={{ paddingLeft: r.group === 'cost' && !isSubtotal ? 24 : 12, color: r.muted ? 'var(--color-fg-muted)' : 'inherit' }}>
                    {isSubtotal && '⏵ '}{r.label}
                  </td>
                  <td className="num mono" style={{ color: isCost ? 'var(--color-delta-negative)' : 'inherit', fontWeight: isSubtotal ? 600 : 400 }}>
                    {F.formatRub(r.cur)}
                  </td>
                  {compare && <td className="num mono" style={{ color: 'var(--color-fg-muted)' }}>{F.formatRub(r.prev)}</td>}
                  {compare && <td className="num mono"><Delta pct={deltaPct} inverted={isCost} /></td>}
                  {compare && <td className="num mono">
                    <span className={delta > 0 ? (isCost ? 'delta down' : 'delta up') : (isCost ? 'delta up' : 'delta down')}>
                      {delta > 0 ? '+' : ''}{F.formatRub(delta).replace('₽', '').trim()} ₽
                    </span>
                  </td>}
                  <td className="num mono" style={{ color: 'var(--color-fg-muted)' }}>{F.formatPct(pctRev)}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </section>

      {/* Detailed by SKU */}
      <section className="card section-card">
        <div className="section-head">
          <div>
            <h3 className="section-title">Прибыль по артикулам</h3>
            <div className="section-subtitle">показано {visible.length} из {D.SKUS.length}</div>
          </div>
          <div className="row">
            <input className="input" placeholder="Найти артикул…" value={filter} onChange={e => setFilter(e.target.value)} style={{ width: 220 }} />
            <button className="btn btn-secondary btn-sm">Колонки <MarkerIcon name="chevDown" size={12}/></button>
            <button className="btn btn-secondary btn-sm"><MarkerIcon name="download" size={14}/>CSV</button>
          </div>
        </div>
        <div className="tbl-wrap">
          <table className="tbl">
            <thead>
              <tr>
                <th className="tbl-checkbox">
                  <input type="checkbox" checked={allSelected} ref={el => el && (el.indeterminate = !allSelected && someSelected)} onChange={toggleAll} />
                </th>
                <th>Артикул</th>
                <th>МП</th>
                <th className="num">Заказы</th>
                <th className="num">Выручка</th>
                {compare && <th className="num">Δ %</th>}
                <th className="num">Маржа</th>
                <th className="num">ROAS</th>
                <th className="num">ДРР</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {visible.map(s => (
                <tr key={s.id} className={selected.has(s.id) ? 'selected' : ''}>
                  <td className="tbl-checkbox" onClick={e => e.stopPropagation()}>
                    <input type="checkbox" checked={selected.has(s.id)} onChange={() => toggleOne(s.id)} />
                  </td>
                  <td>
                    <span className="tbl-link" onClick={() => onOpenSku(s)}>{s.id}</span>
                    <div style={{ fontSize: 12, color: 'var(--color-fg-muted)', marginTop: 2 }}>{s.name}</div>
                  </td>
                  <td>{s.mp.map(m => <MpBadge key={m} mp={m} />)}</td>
                  <td className="num mono">{F.formatInt(s.orders)}</td>
                  <td className="num mono" style={{ fontWeight: 600 }}>{F.formatRub(s.revenue)}</td>
                  {compare && <td className="num mono"><Delta pct={s.deltaPct} /></td>}
                  <td className="num mono">{F.formatPct(s.margin * 100)}</td>
                  <td className="num mono">{F.formatMul(s.roas)}</td>
                  <td className="num mono">{F.formatPct((s.adsCost / s.revenue) * 100)}</td>
                  <td><button className="icon-btn"><MarkerIcon name="moreV" size={14}/></button></td>
                </tr>
              ))}
            </tbody>
            <tfoot>
              <tr>
                <td className="tbl-checkbox"></td>
                <td>Итого ({visible.length})</td>
                <td></td>
                <td className="num mono">{F.formatInt(visible.reduce((s, x) => s + x.orders, 0))}</td>
                <td className="num mono">{F.formatRub(visible.reduce((s, x) => s + x.revenue, 0))}</td>
                {compare && <td></td>}
                <td className="num mono">{F.formatPct(visible.reduce((s, x) => s + x.margin, 0) / visible.length * 100)}</td>
                <td></td><td></td><td></td>
              </tr>
            </tfoot>
          </table>
        </div>
      </section>
    </>
  );
}

window.MarkerPnL = PnL;
