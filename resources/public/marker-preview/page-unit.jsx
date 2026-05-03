// Unit Economics — What-if calculator
function UnitEcon({ density }) {
  const F = window.MarkerFmt;
  const [params, setParams] = useStateP({
    price: 2500,
    cogs: 1200,
    commission: 17,
    logistics: 90,
    returns: 8,
    ads: 220,
  });
  const baseline = { price: 2500, cogs: 1200, commission: 17, logistics: 90, returns: 8, ads: 220 };
  const set = (k, v) => setParams(p => ({ ...p, [k]: v }));

  const calc = (p) => {
    const commissionAmt = p.price * p.commission / 100;
    const returnsAmt = p.price * p.returns / 100;
    const totalCost = p.cogs + commissionAmt + p.logistics + returnsAmt + p.ads;
    const profit = p.price - totalCost;
    const margin = (profit / p.price) * 100;
    const roas = p.ads > 0 ? p.price / p.ads : 0;
    return { profit, margin, roas, totalCost };
  };
  const cur = calc(params);
  const base = calc(baseline);
  const dProfit = ((cur.profit - base.profit) / Math.abs(base.profit)) * 100;
  const dMargin = cur.margin - base.margin;

  const sliders = [
    { k: 'price', label: 'Цена розничная, ₽', min: 1000, max: 5000, step: 50, fmt: v => F.formatRub(v) },
    { k: 'cogs',  label: 'Себестоимость, ₽',   min: 400,  max: 2500, step: 25, fmt: v => F.formatRub(v) },
    { k: 'commission', label: 'Комиссия МП, %', min: 5, max: 30, step: 0.5, fmt: v => F.formatPct(v) },
    { k: 'logistics',  label: 'Логистика, ₽',   min: 30, max: 250, step: 5, fmt: v => F.formatRub(v) },
    { k: 'returns',    label: 'Возвраты, %',    min: 0, max: 25, step: 0.5, fmt: v => F.formatPct(v) },
    { k: 'ads',        label: 'Реклама, ₽/шт',  min: 0, max: 600, step: 10, fmt: v => F.formatRub(v) },
  ];

  return (
    <div className="grid-12">
      <section className="card section-card col-7">
        <div className="section-head">
          <div>
            <h3 className="section-title">Параметры</h3>
            <div className="section-subtitle">передвиньте слайдер — расчёт обновится мгновенно</div>
          </div>
          <button className="btn btn-secondary btn-sm" onClick={() => setParams(baseline)}>Сбросить</button>
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
          {sliders.map(s => (
            <div key={s.k} className="slider-row">
              <div className="head">
                <span className="label">{s.label}</span>
                <span className="val">{s.fmt(params[s.k])}</span>
              </div>
              <input type="range" min={s.min} max={s.max} step={s.step} value={params[s.k]}
                     onChange={e => set(s.k, parseFloat(e.target.value))} />
              <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 10, color: 'var(--color-fg-muted)' }}>
                <span>{s.fmt(s.min)}</span>
                <span style={{ color: 'var(--color-fg-disabled)' }}>baseline {s.fmt(baseline[s.k])}</span>
                <span>{s.fmt(s.max)}</span>
              </div>
            </div>
          ))}
        </div>
        <div style={{ marginTop: 24, paddingTop: 16, borderTop: '1px solid var(--color-border-subtle)', display: 'flex', gap: 8 }}>
          <button className="btn btn-primary btn-sm">Применить как основной</button>
          <button className="btn btn-ghost btn-sm">Сохранить как сценарий</button>
        </div>
      </section>

      <section className="col-5" style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
        <div className="card section-card">
          <div className="uppercase-label">Маржа</div>
          <div style={{ fontSize: 36, fontWeight: 700, marginTop: 4 }}>{F.formatPct(cur.margin)}</div>
          <div style={{ marginTop: 6 }}>
            <Delta pct={dMargin} suffix=" п.п. vs baseline" />
          </div>
          <div style={{ marginTop: 12, height: 6, background: 'var(--color-bg-muted)', borderRadius: 999, overflow: 'hidden' }}>
            <div style={{ height: '100%', width: `${Math.max(0, Math.min(100, cur.margin * 2))}%`, background: cur.margin > 25 ? 'var(--color-delta-positive)' : cur.margin > 15 ? 'var(--color-warning-fg)' : 'var(--color-delta-negative)', transition: 'width 200ms' }} />
          </div>
        </div>
        <div className="card section-card">
          <div className="uppercase-label">Прибыль с штуки</div>
          <div style={{ fontSize: 28, fontWeight: 700, marginTop: 4 }}>{F.formatRub(cur.profit)}</div>
          <div style={{ marginTop: 6 }}><Delta pct={dProfit} suffix=" vs baseline" /></div>
        </div>
        <div className="card section-card">
          <div className="uppercase-label">ROAS</div>
          <div style={{ fontSize: 28, fontWeight: 700, marginTop: 4 }}>{F.formatMul(cur.roas)}</div>
        </div>
        <div className="card section-card">
          <div className="uppercase-label">Структура затрат</div>
          <div style={{ marginTop: 10, display: 'flex', flexDirection: 'column', gap: 8 }}>
            {[
              { label: 'Себестоимость', val: params.cogs, color: 'var(--chart-1)' },
              { label: 'Комиссия МП', val: params.price * params.commission / 100, color: 'var(--chart-2)' },
              { label: 'Логистика', val: params.logistics, color: 'var(--chart-3)' },
              { label: 'Возвраты', val: params.price * params.returns / 100, color: 'var(--chart-4)' },
              { label: 'Реклама', val: params.ads, color: 'var(--chart-5)' },
            ].map(row => (
              <div key={row.label} style={{ display: 'flex', alignItems: 'center', gap: 10, fontSize: 12 }}>
                <span style={{ width: 8, height: 8, borderRadius: 2, background: row.color }} />
                <span style={{ flex: 1 }}>{row.label}</span>
                <span className="mono">{F.formatRub(row.val)}</span>
                <span className="mono" style={{ color: 'var(--color-fg-muted)', minWidth: 44, textAlign: 'right' }}>
                  {((row.val / params.price) * 100).toFixed(0)}%
                </span>
              </div>
            ))}
          </div>
        </div>
      </section>
    </div>
  );
}

window.MarkerUnit = UnitEcon;
