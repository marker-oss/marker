// Products page (table + drill-down trigger) and UI Kit
function Products({ mpFilter, onOpenSku }) {
  const D = window.MARKER_DATA;
  const F = window.MarkerFmt;
  const [view, setView] = useStateP('grid');

  const visible = D.SKUS.filter(s => mpFilter.length === 0 || s.mp.some(m => mpFilter.includes(m)));

  return (
    <>
      <section className="card section-card">
        <div className="section-head">
          <div>
            <h3 className="section-title">Каталог товаров</h3>
            <div className="section-subtitle">{visible.length} {F.pluralRu(visible.length, 'артикул', 'артикула', 'артикулов')}</div>
          </div>
          <div className="row">
            <div style={{ display: 'flex', border: '1px solid var(--color-border-subtle)', borderRadius: 6, padding: 2 }}>
              <button className={`btn btn-sm ${view === 'grid' ? 'btn-secondary' : 'btn-ghost'}`} onClick={() => setView('grid')} style={{ height: 26 }}>
                <MarkerIcon name="layout" size={14}/>Карточки
              </button>
              <button className={`btn btn-sm ${view === 'list' ? 'btn-secondary' : 'btn-ghost'}`} onClick={() => setView('list')} style={{ height: 26 }}>
                <MarkerIcon name="layers" size={14}/>Список
              </button>
            </div>
            <button className="btn btn-secondary btn-sm"><MarkerIcon name="plus" size={14}/>Артикул</button>
          </div>
        </div>

        {view === 'grid' ? (
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))', gap: 14 }}>
            {visible.slice(0, 18).map(s => (
              <button key={s.id} onClick={() => onOpenSku(s)}
                style={{ background: 'var(--color-bg-surface)', border: '1px solid var(--color-border-subtle)', borderRadius: 'var(--radius-lg)', padding: 14, textAlign: 'left', display: 'flex', flexDirection: 'column', gap: 8, cursor: 'pointer', transition: 'border-color 100ms, box-shadow 100ms' }}
                onMouseEnter={e => { e.currentTarget.style.borderColor = 'var(--color-border-strong)'; e.currentTarget.style.boxShadow = 'var(--shadow-sm)'; }}
                onMouseLeave={e => { e.currentTarget.style.borderColor = 'var(--color-border-subtle)'; e.currentTarget.style.boxShadow = 'none'; }}>
                <div style={{ aspectRatio: '16/10', background: 'repeating-linear-gradient(135deg, var(--color-bg-subtle), var(--color-bg-subtle) 8px, var(--color-bg-muted) 8px, var(--color-bg-muted) 16px)', borderRadius: 6, display: 'grid', placeItems: 'center', color: 'var(--color-fg-muted)', fontFamily: 'var(--font-mono)', fontSize: 11 }}>
                  {s.id}
                </div>
                <div className="row"><span className="mono" style={{ fontSize: 11, color: 'var(--color-fg-muted)' }}>{s.id}</span>{s.mp.map(m => <MpBadge key={m} mp={m} />)}</div>
                <div style={{ fontSize: 13, fontWeight: 500 }}>{s.name}</div>
                <div className="row" style={{ marginTop: 'auto' }}>
                  <span className="mono" style={{ fontWeight: 600 }}>{F.formatRub(s.revenue)}</span>
                  <Delta pct={s.deltaPct} />
                  <div className="spacer"/>
                  <Sparkline data={s.spark} width={60} height={20} />
                </div>
              </button>
            ))}
          </div>
        ) : (
          <table className="tbl">
            <thead><tr><th>Артикул</th><th>МП</th><th className="num">Заказы</th><th className="num">Выручка</th><th className="num">Маржа</th><th className="num">Остаток</th><th></th></tr></thead>
            <tbody>{visible.map(s => (
              <tr key={s.id} onClick={() => onOpenSku(s)} style={{ cursor: 'pointer' }}>
                <td><span className="tbl-link">{s.id}</span> · {s.name}</td>
                <td>{s.mp.map(m => <MpBadge key={m} mp={m}/>)}</td>
                <td className="num mono">{F.formatInt(s.orders)}</td>
                <td className="num mono">{F.formatRub(s.revenue)}</td>
                <td className="num mono">{F.formatPct(s.margin * 100)}</td>
                <td className="num mono">{F.formatInt(s.stock)}</td>
                <td><Sparkline data={s.spark} width={60} height={20} /></td>
              </tr>
            ))}</tbody>
          </table>
        )}
      </section>
    </>
  );
}

// SKU drill-down content
function SkuDetail({ sku, onClose }) {
  const F = window.MarkerFmt;
  const chartRef = useRefP(null);
  useEffectP(() => {
    if (!sku || !window.Chart || !chartRef.current) return;
    const ctx = chartRef.current.getContext('2d');
    const labels = Array.from({ length: 30 }, (_, i) => `${String(i + 1).padStart(2, '0')}.05`);
    const c = new window.Chart(ctx, {
      type: 'line',
      data: { labels, datasets: [{ label: 'Выручка', data: sku.spark.map(v => Math.round(v)), borderColor: '#4f46e5', backgroundColor: 'rgba(79,70,229,0.12)', fill: true, tension: 0.3, borderWidth: 2, pointRadius: 0 }]},
      options: { responsive: true, maintainAspectRatio: false, plugins: { legend: { display: false }, tooltip: { backgroundColor: '#0f172a' } }, scales: { x: { grid: { display: false }, ticks: { font: { size: 10, family: 'Inter' }, color: '#94a3b8', maxTicksLimit: 6 } }, y: { grid: { color: '#f1f5f9' }, ticks: { font: { size: 10, family: 'Inter' }, color: '#94a3b8', callback: v => F.formatShort(v) }, beginAtZero: true } } }
    });
    return () => c.destroy();
  }, [sku]);

  if (!sku) return null;
  const planPct = Math.round((sku.revenue / sku.plan) * 100);

  return (
    <>
      <div className="sheet-head">
        <div>
          <div className="row">
            <span className="mono" style={{ fontSize: 12, color: 'var(--color-fg-muted)' }}>{sku.id}</span>
            {sku.mp.map(m => <MpBadge key={m} mp={m} />)}
          </div>
          <div style={{ fontSize: 18, fontWeight: 600, marginTop: 4 }}>{sku.name}</div>
        </div>
        <div className="row">
          <button className="icon-btn"><MarkerIcon name="moreH"/></button>
          <button className="icon-btn" onClick={onClose}><MarkerIcon name="x"/></button>
        </div>
      </div>
      <div className="sheet-body">
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: 10 }}>
          {[
            { l: 'Выручка 30 дн', v: F.formatRub(sku.revenue), d: sku.deltaPct },
            { l: 'Заказы',  v: F.formatInt(sku.orders) + ' шт', d: sku.deltaPct * 0.7 },
            { l: 'Маржа',  v: F.formatPct(sku.margin * 100), d: -1.5 },
            { l: 'ROAS',   v: F.formatMul(sku.roas), d: 4.2 },
          ].map((k, i) => (
            <div key={i} className="card" style={{ padding: 12 }}>
              <div className="uppercase-label">{k.l}</div>
              <div style={{ fontSize: 18, fontWeight: 600, marginTop: 4 }}>{k.v}</div>
              <div style={{ marginTop: 4 }}><Delta pct={k.d} /></div>
            </div>
          ))}
        </div>

        <div className="card section-card">
          <div className="section-head"><h3 className="section-title" style={{ fontSize: 13 }}>Динамика выручки</h3></div>
          <div style={{ height: 160 }}><canvas ref={chartRef} /></div>
        </div>

        <div className="card section-card">
          <div className="section-head"><h3 className="section-title" style={{ fontSize: 13 }}>План — факт</h3><span className="badge badge-info">{planPct}%</span></div>
          <div className="row" style={{ marginBottom: 8 }}>
            <span className="mono" style={{ fontSize: 16, fontWeight: 600 }}>{F.formatRub(sku.revenue)}</span>
            <span style={{ color: 'var(--color-fg-muted)', fontSize: 12 }}>из {F.formatRub(sku.plan)}</span>
          </div>
          <div className={`progress ${planPct >= 100 ? 'success' : planPct >= 80 ? 'warning' : ''}`}><div style={{ width: `${Math.min(100, planPct)}%` }}/></div>
        </div>

        <div className="card section-card">
          <div className="section-head"><h3 className="section-title" style={{ fontSize: 13 }}>Остатки</h3></div>
          <table className="tbl">
            <thead><tr><th>МП</th><th className="num">Шт</th><th className="num">Скорость</th><th className="num">Дней</th></tr></thead>
            <tbody>
              {sku.mp.map(m => {
                const stock = Math.round(sku.stock * (m === 'wb' ? 0.6 : m === 'ozon' ? 0.3 : 0.1));
                const speed = Math.max(1, Math.round(sku.orders / 30 * (m === 'wb' ? 0.6 : 0.3)));
                return <tr key={m}><td><span className="row"><MpBadge mp={m}/>{m.toUpperCase()}</span></td><td className="num mono">{F.formatInt(stock)}</td><td className="num mono">{speed}/день</td><td className="num mono">{Math.round(stock / speed)}</td></tr>;
              })}
            </tbody>
          </table>
        </div>
      </div>
      <div className="sheet-foot">
        <button className="btn btn-ghost" onClick={onClose}>Закрыть</button>
        <button className="btn btn-primary">Открыть полную страницу <MarkerIcon name="arrowRight" size={14}/></button>
      </div>
    </>
  );
}

// UI Kit page
function UIKit() {
  return (
    <>
      <section className="card section-card">
        <div className="section-head"><h3 className="section-title">Цвета</h3><div className="section-subtitle">семантические токены</div></div>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(6, 1fr)', gap: 8 }}>
          {[
            ['bg-app','#f8fafc'],['bg-surface','#ffffff'],['bg-subtle','#f1f5f9'],
            ['fg-primary','#0f172a'],['fg-secondary','#334155'],['fg-muted','#64748b'],
            ['accent-primary','#1e293b'],['accent-interactive','#4f46e5'],['delta-positive','#16a34a'],
            ['delta-negative','#dc2626'],['warning-fg','#92400e'],['mp-wb','#CB11AB'],
          ].map(([k, v]) => (
            <div key={k} style={{ border: '1px solid var(--color-border-subtle)', borderRadius: 6, overflow: 'hidden' }}>
              <div style={{ height: 56, background: v }} />
              <div style={{ padding: 8, fontSize: 11 }}>
                <div className="mono">{k}</div>
                <div className="muted mono">{v}</div>
              </div>
            </div>
          ))}
        </div>
      </section>

      <section className="card section-card">
        <div className="section-head"><h3 className="section-title">Кнопки</h3></div>
        <div className="row" style={{ flexWrap: 'wrap', gap: 8 }}>
          <button className="btn btn-primary">Primary</button>
          <button className="btn btn-secondary">Secondary</button>
          <button className="btn btn-ghost">Ghost</button>
          <button className="btn btn-link">Link</button>
          <button className="btn btn-primary btn-sm">Small</button>
          <button className="btn btn-primary btn-lg">Large</button>
          <button className="btn btn-primary" disabled>Disabled</button>
          <button className="btn btn-secondary"><MarkerIcon name="download" size={14}/>С иконкой</button>
        </div>
      </section>

      <section className="card section-card">
        <div className="section-head"><h3 className="section-title">Бейджи и chips</h3></div>
        <div className="row" style={{ flexWrap: 'wrap', gap: 8 }}>
          <span className="badge badge-success">success</span>
          <span className="badge badge-warning">warning</span>
          <span className="badge badge-danger">danger</span>
          <span className="badge badge-info">info</span>
          <span className="badge badge-neutral">neutral</span>
          <span className="chip is-active">chip активный</span>
          <span className="chip">chip default</span>
          <span className="chip chip-mp-wb"><MpBadge mp="wb"/>Wildberries</span>
          <span className="chip chip-mp-ozon"><MpBadge mp="ozon"/>Ozon</span>
          <span className="chip chip-mp-ym"><MpBadge mp="ym"/>YM</span>
        </div>
      </section>

      <section className="card section-card">
        <div className="section-head"><h3 className="section-title">Delta-индикаторы</h3></div>
        <div className="row" style={{ gap: 24 }}>
          <Delta pct={12.4} />
          <Delta pct={-3.2} />
          <Delta pct={0.02} />
          <Delta pct={5.5} inverted />
          <Delta pct={-1.8} inverted />
        </div>
      </section>

      <section className="card section-card">
        <div className="section-head"><h3 className="section-title">Поля ввода</h3></div>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 14, maxWidth: 720 }}>
          <div><label className="field-label">Текст</label><input className="input" placeholder="Введите…"/></div>
          <div><label className="field-label">Число</label><input className="input mono" defaultValue="1 234 567" style={{ textAlign: 'right' }}/></div>
          <div><label className="field-label">Селект</label><select className="input select"><option>Все МП</option><option>WB</option><option>Ozon</option></select></div>
        </div>
      </section>

      <section className="card section-card">
        <div className="section-head"><h3 className="section-title">Алерты</h3></div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          {['info','success','warning','danger'].map(k => (
            <div key={k} className={`alert alert-${k}`}>
              <MarkerIcon name={k === 'danger' ? 'danger' : k === 'warning' ? 'warning' : k === 'success' ? 'success' : 'info'} className="alert-icon"/>
              <div className="alert-body">
                <div className="alert-title">Заголовок alert · {k}</div>
                <div>Тестовое описание для демонстрации компонента.</div>
              </div>
            </div>
          ))}
        </div>
      </section>

      <section className="card section-card">
        <div className="section-head"><h3 className="section-title">Прогресс</h3></div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 14, maxWidth: 600 }}>
          {[{ p: 35, l: 'danger' }, { p: 62, l: '' }, { p: 88, l: 'warning' }, { p: 110, l: 'success' }].map((x, i) => (
            <div key={i}><div className="row"><span className="muted" style={{ fontSize: 12 }}>{x.p}% выполнения</span></div>
              <div className={`progress ${x.l}`}><div style={{ width: `${Math.min(100, x.p)}%` }}/></div>
            </div>
          ))}
        </div>
      </section>

      <section className="card section-card">
        <div className="section-head"><h3 className="section-title">Skeleton</h3></div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8, maxWidth: 400 }}>
          <div className="skel" style={{ height: 12, width: '40%' }}/>
          <div className="skel" style={{ height: 24, width: '70%' }}/>
          <div className="skel" style={{ height: 12, width: '90%' }}/>
          <div className="skel" style={{ height: 80 }}/>
        </div>
      </section>
    </>
  );
}

window.MarkerProducts = Products;
window.MarkerSkuDetail = SkuDetail;
window.MarkerUIKit = UIKit;
