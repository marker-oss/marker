// Pulse Dashboard page
const { useState: useStateP, useEffect: useEffectP, useRef: useRefP, useMemo: useMemoP } = React;

function Pulse({ compare, mpFilter, density, kpiVariant, onOpenSku }) {
  const D = window.MARKER_DATA;
  const F = window.MarkerFmt;
  const lineRef = useRefP(null);
  const barRef = useRefP(null);
  const donutRef = useRefP(null);

  // Build chart configs once
  useEffectP(() => {
    if (!window.Chart) return;
    const labels = Array.from({ length: 30 }, (_, i) => `${String(i + 1).padStart(2, '0')}.05`);
    const css = getComputedStyle(document.documentElement);
    const fgMuted = css.getPropertyValue('--color-fg-muted').trim() || '#94a3b8';
    const borderSubtle = css.getPropertyValue('--color-border-subtle').trim() || '#e2e8f0';
    const bgSubtle = css.getPropertyValue('--color-bg-subtle').trim() || '#f1f5f9';
    const slate900 = '#0f172a';
    const baseFont = { family: 'Inter', size: 11 };
    const commonOpts = {
      responsive: true, maintainAspectRatio: false,
      plugins: {
        legend: { position: 'bottom', align: 'start', labels: { boxWidth: 10, boxHeight: 10, padding: 14, font: baseFont, color: '#475569' } },
        tooltip: {
          backgroundColor: slate900, titleColor: '#fff', bodyColor: '#cbd5e1',
          borderColor: '#334155', borderWidth: 1, cornerRadius: 6, padding: 10,
          titleFont: { ...baseFont, weight: 600 }, bodyFont: baseFont,
        },
      },
      scales: {
        x: { grid: { display: false }, ticks: { font: baseFont, color: fgMuted, maxTicksLimit: 10 }, border: { color: borderSubtle } },
        y: { grid: { color: bgSubtle, drawBorder: false }, ticks: { font: baseFont, color: fgMuted, callback: v => F.formatShort(v) }, border: { display: false }, beginAtZero: true },
      },
    };

    let line, bar, donut;
    if (lineRef.current) {
      const ctx = lineRef.current.getContext('2d');
      const datasets = [{
        label: 'Выручка', data: D.REVENUE_SERIES, borderColor: '#4f46e5', backgroundColor: 'rgba(79,70,229,0.12)',
        fill: true, tension: 0.3, borderWidth: 2, pointRadius: 0, pointHoverRadius: 4,
      }];
      if (compare) {
        datasets.push({
          label: 'Пред. период', data: D.REVENUE_SERIES.map((v, i) => v * (0.85 + (i % 5) * 0.02)),
          borderColor: '#94a3b8', borderDash: [4, 4], borderWidth: 1.5, pointRadius: 0, fill: false, tension: 0.3,
        });
      }
      line = new window.Chart(ctx, { type: 'line', data: { labels, datasets }, options: commonOpts });
    }
    if (barRef.current) {
      const ctx = barRef.current.getContext('2d');
      const wb = D.ORDERS_SERIES.map(v => v * 0.55);
      const oz = D.ORDERS_SERIES.map(v => v * 0.30);
      const ym = D.ORDERS_SERIES.map(v => v * 0.15);
      bar = new window.Chart(ctx, {
        type: 'bar',
        data: { labels, datasets: [
          { label: 'WB', data: wb, backgroundColor: '#4f46e5', stack: 's', borderRadius: 2, barThickness: 10 },
          { label: 'Ozon', data: oz, backgroundColor: '#0891b2', stack: 's', borderRadius: 2, barThickness: 10 },
          { label: 'YM', data: ym, backgroundColor: '#ca8a04', stack: 's', borderRadius: 2, barThickness: 10 },
        ]},
        options: { ...commonOpts, scales: { ...commonOpts.scales, x: { ...commonOpts.scales.x, stacked: true }, y: { ...commonOpts.scales.y, stacked: true } } }
      });
    }
    if (donutRef.current) {
      const ctx = donutRef.current.getContext('2d');
      donut = new window.Chart(ctx, {
        type: 'doughnut',
        data: { labels: ['WB', 'Ozon', 'YM'], datasets: [{
          data: [62, 26, 12],
          backgroundColor: ['#4f46e5', '#0891b2', '#ca8a04'],
          borderWidth: 0, hoverOffset: 6,
        }]},
        options: { ...commonOpts, cutout: '70%', scales: {} }
      });
    }
    return () => { line?.destroy(); bar?.destroy(); donut?.destroy(); };
  }, [compare]);

  const totals = useMemoP(() => {
    const sum = arr => arr.reduce((a, b) => a + b, 0);
    return {
      revenue: sum(D.REVENUE_SERIES),
      profit: sum(D.PROFIT_SERIES),
      orders: Math.round(sum(D.ORDERS_SERIES)),
      ads: sum(D.ADS_SERIES),
    };
  }, []);

  const kpis = [
    { label: 'Выручка', value: F.formatRub(totals.revenue), delta: 12.4, spark: D.REVENUE_SERIES, sub: 'WoW', icon: 'finance' },
    { label: 'Чистая прибыль', value: F.formatRub(totals.profit), delta: 8.2, spark: D.PROFIT_SERIES, sub: 'WoW', icon: 'activity' },
    { label: 'Заказы', value: F.formatInt(totals.orders) + ' шт', delta: 5.8, spark: D.ORDERS_SERIES, sub: 'WoW', icon: 'box' },
    { label: 'Маржа', value: F.formatPct(34.2), delta: -2.1, spark: D.PROFIT_SERIES, sub: 'WoW', icon: 'sparkles' },
    { label: 'Средний чек', value: F.formatRub(2840), delta: 1.4, sub: 'WoW' },
    { label: 'Выкуп', value: F.formatPct(78.4), delta: 0.8, sub: 'WoW' },
    { label: 'ROAS', value: F.formatMul(3.4), delta: -0.6, sub: 'WoW' },
    { label: 'ДРР', value: F.formatPct(11.2), delta: 1.2, sub: 'WoW', invert: true },
  ];

  const planPct = Math.round((D.FORECAST.monthFact / D.FORECAST.monthPlan) * 100);
  const projectionPct = Math.round((D.FORECAST.projection / D.FORECAST.monthPlan) * 100);

  return (
    <>
      {/* Alerts */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
        {D.ALERTS.map((a, i) => (
          <div key={i} className={`alert alert-${a.kind}`}>
            <MarkerIcon name={a.kind === 'danger' ? 'danger' : a.kind === 'warning' ? 'warning' : 'info'} className="alert-icon" />
            <div className="alert-body">
              <div className="alert-title">{a.title}</div>
              <div>{a.body}</div>
            </div>
            <button className="btn btn-ghost btn-sm" style={{ color: 'inherit', border: '1px solid currentColor' }}>{a.cta}</button>
          </div>
        ))}
      </div>

      {/* KPI grid — variant A: standard, B: with sparklines */}
      <section className="card section-card">
        <div className="section-head">
          <div>
            <h3 className="section-title">Ключевые метрики</h3>
            {compare && <div className="section-subtitle">vs предыдущий период (24.04 — 23.05)</div>}
          </div>
          <div className="row">
            <span className="badge badge-success"><span className="dot-status green" /> Данные на 03.05.2026 11:24</span>
          </div>
        </div>
        <div className="kpi-grid">
          {kpis.map((k, i) => (
            <div key={i} className="kpi">
              <div className="kpi-label">{k.label}</div>
              <div className="kpi-value">{k.value}</div>
              <div className="kpi-foot">
                {compare && <Delta pct={k.delta} inverted={k.invert} />}
                {compare && <span style={{ color: 'var(--color-fg-muted)' }}>{k.sub}</span>}
                {!compare && <span>{k.label === 'Выручка' ? 'за 30 дней' : ''}</span>}
              </div>
              {kpiVariant === 'spark' && k.spark && (
                <div style={{ position: 'absolute', right: 14, top: 14 }}>
                  <Sparkline data={k.spark} width={72} height={26} />
                </div>
              )}
            </div>
          ))}
        </div>
      </section>

      {/* Plan-fact + Donut */}
      <div className="grid-12">
        <section className="card section-card col-8">
          <div className="section-head">
            <div>
              <h3 className="section-title">План — факт. Прибыль за май 2026</h3>
              <div className="section-subtitle">Pace: 84% · прогноз достижения {projectionPct}% от цели</div>
            </div>
            <span className="badge badge-warning">⚠ Цель будет не достигнута</span>
          </div>
          <div style={{ display: 'flex', alignItems: 'baseline', gap: 16, marginBottom: 12 }}>
            <div style={{ fontSize: 32, fontWeight: 700, letterSpacing: '-.01em' }}>{F.formatRub(D.FORECAST.monthFact)}</div>
            <div style={{ color: 'var(--color-fg-muted)' }}>из {F.formatRub(D.FORECAST.monthPlan)} плана</div>
            <div className="spacer" />
            <div style={{ fontSize: 14, fontWeight: 600 }}>{planPct}%</div>
          </div>
          <div className="progress warning"><div style={{ width: `${planPct}%` }} /></div>
          <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 8, fontSize: 12, color: 'var(--color-fg-muted)' }}>
            <span>0</span><span>50%</span><span>100% цель</span>
          </div>
          <div style={{ marginTop: 18, height: 220 }}>
            <canvas ref={lineRef} />
          </div>
        </section>

        <section className="card section-card col-4">
          <div className="section-head">
            <div>
              <h3 className="section-title">Структура выручки</h3>
              <div className="section-subtitle">по маркетплейсам</div>
            </div>
          </div>
          <div style={{ height: 180, position: 'relative' }}>
            <canvas ref={donutRef} />
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6, marginTop: 14 }}>
            {[
              { mp: 'wb', label: 'Wildberries', val: 62, sum: 5220400 },
              { mp: 'ozon', label: 'Ozon', val: 26, sum: 2189200 },
              { mp: 'ym', label: 'YM', val: 12, sum: 1010400 },
            ].map(r => (
              <div key={r.mp} style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 12 }}>
                <MpBadge mp={r.mp} />
                <span style={{ flex: 1 }}>{r.label}</span>
                <span className="mono">{F.formatRub(r.sum)}</span>
                <span className="mono" style={{ color: 'var(--color-fg-muted)', minWidth: 36, textAlign: 'right' }}>{r.val}%</span>
              </div>
            ))}
          </div>
        </section>
      </div>

      {/* Bar chart + Top movers/fallers */}
      <div className="grid-12">
        <section className="card section-card col-7">
          <div className="section-head">
            <div>
              <h3 className="section-title">Заказы по дням</h3>
              <div className="section-subtitle">stack по маркетплейсам</div>
            </div>
            <div className="row">
              <button className="icon-btn" title="Развернуть"><MarkerIcon name="expand" /></button>
              <button className="icon-btn"><MarkerIcon name="moreH" /></button>
            </div>
          </div>
          <div style={{ height: 240 }}>
            <canvas ref={barRef} />
          </div>
        </section>

        <section className="card section-card col-5">
          <Tabs tabs={[
            { id: 'movers', label: 'Топ роста', count: 5 },
            { id: 'fallers', label: 'Топ падения', count: 5 },
          ]} render={(active) => (
            <div style={{ marginTop: 12 }}>
              {(active === 'movers' ? D.TOP_MOVERS : D.TOP_FALLERS).map(s => (
                <div key={s.id} onClick={() => onOpenSku(s)}
                     style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '10px 4px', borderBottom: '1px solid var(--color-border-subtle)', cursor: 'pointer' }}>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 2, minWidth: 0, flex: 1 }}>
                    <div className="row">
                      <span className="mono" style={{ fontSize: 12, color: 'var(--color-fg-muted)' }}>{s.id}</span>
                      {s.mp.map(m => <MpBadge key={m} mp={m} />)}
                    </div>
                    <div style={{ fontSize: 13, fontWeight: 500 }}>{s.name}</div>
                  </div>
                  <Sparkline data={s.spark} />
                  <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', minWidth: 90 }}>
                    <span className="mono" style={{ fontSize: 13, fontWeight: 600 }}>{F.formatRub(s.revenue)}</span>
                    <Delta pct={s.deltaPct} />
                  </div>
                </div>
              ))}
            </div>
          )} />
        </section>
      </div>

      {/* Stock alerts table */}
      <section className="card section-card">
        <div className="section-head">
          <div>
            <h3 className="section-title">Остатки — критично</h3>
            <div className="section-subtitle">артикулы, которые кончатся за ≤ 14 дней</div>
          </div>
          <button className="btn btn-secondary btn-sm">Все остатки →</button>
        </div>
        <table className="tbl">
          <thead>
            <tr>
              <th>Артикул</th>
              <th>МП</th>
              <th className="num">Остаток, шт</th>
              <th className="num">Скорость / день</th>
              <th className="num">Дней до 0</th>
              <th>Статус</th>
            </tr>
          </thead>
          <tbody>
            {D.SKUS.slice(0, 6).map((s, i) => {
              const speed = Math.max(2, Math.round(s.orders / 30));
              const days = Math.round(s.stock / speed);
              const status = days < 4 ? 'danger' : days < 8 ? 'warning' : 'success';
              return (
                <tr key={s.id} onClick={() => onOpenSku(s)} style={{ cursor: 'pointer' }}>
                  <td><span className="tbl-link">{s.id}</span> · <span style={{ color: 'var(--color-fg-secondary)' }}>{s.name}</span></td>
                  <td>{s.mp.map(m => <MpBadge key={m} mp={m} />)}</td>
                  <td className="num mono">{F.formatInt(s.stock)}</td>
                  <td className="num mono">{speed}</td>
                  <td className="num mono">{days}</td>
                  <td><span className={`badge badge-${status}`}>{status === 'danger' ? 'Критично' : status === 'warning' ? 'Низкий' : 'OK'}</span></td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </section>
    </>
  );
}

function Tabs({ tabs, render }) {
  const [active, setActive] = useStateP(tabs[0].id);
  return (
    <>
      <div className="tabs">
        {tabs.map(t => (
          <button key={t.id} className={`tab ${active === t.id ? 'active' : ''}`} onClick={() => setActive(t.id)}>
            {t.label}
            {t.count != null && <span className="tab-counter">{t.count}</span>}
          </button>
        ))}
      </div>
      {render(active)}
    </>
  );
}

window.MarkerPulse = Pulse;
window.MarkerTabs = Tabs;
