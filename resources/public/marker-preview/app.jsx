// Main app — wires everything together
const { useState: uS, useEffect: uE, useRef: uR } = React;

const TWEAK_DEFAULTS = /*EDITMODE-BEGIN*/{
  "theme": "light",
  "density": "standard",
  "sidebar": "expanded",
  "compare": false,
  "kpiVariant": "spark"
}/*EDITMODE-END*/;

function App() {
  const [page, setPage] = uS('pulse');
  const [tweaks, setTweaks] = uS(TWEAK_DEFAULTS);
  const [mpFilter, setMpFilter] = uS(['wb', 'ozon', 'ym']);
  const [period, setPeriod] = uS('Май 2026');
  const [cmdkOpen, setCmdkOpen] = uS(false);
  const [sheetSku, setSheetSku] = uS(null);
  const [syncState, setSyncState] = uS(null);
  const [tweaksOpen, setTweaksOpen] = uS(false);

  // Apply tweaks to document
  uE(() => {
    document.documentElement.dataset.theme = tweaks.theme;
    document.documentElement.dataset.density = tweaks.density;
    document.documentElement.dataset.sidebar = tweaks.sidebar;
  }, [tweaks]);

  const setTweak = (k, v) => {
    setTweaks(t => ({ ...t, [k]: v }));
    try { window.parent.postMessage({ type: '__edit_mode_set_keys', edits: { [k]: v } }, '*'); } catch (e) {}
  };

  // Cmd+K
  uE(() => {
    const onKey = (e) => {
      if ((e.metaKey || e.ctrlKey) && e.key === 'k') { e.preventDefault(); setCmdkOpen(true); }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  // Tweaks bridge
  uE(() => {
    const onMsg = (e) => {
      if (e.data?.type === '__activate_edit_mode') setTweaksOpen(true);
      if (e.data?.type === '__deactivate_edit_mode') setTweaksOpen(false);
    };
    window.addEventListener('message', onMsg);
    try { window.parent.postMessage({ type: '__edit_mode_available' }, '*'); } catch (e) {}
    return () => window.removeEventListener('message', onMsg);
  }, []);

  const startSync = () => {
    setSyncState({ kind: 'running', section: 'WB sales', elapsed: '0 сек', progress: 0 });
    let p = 0;
    const t = setInterval(() => {
      p += 7 + Math.random() * 12;
      if (p >= 100) {
        clearInterval(t);
        setSyncState({ kind: 'success', time: new Date().toLocaleTimeString('ru', { hour: '2-digit', minute: '2-digit' }) });
        setTimeout(() => setSyncState(null), 5000);
      } else {
        setSyncState({ kind: 'running', section: 'WB sales', elapsed: `${Math.round(p / 7)} сек`, progress: Math.round(p) });
      }
    }, 400);
  };

  const onNav = (id, opts) => {
    setPage(id);
    if (opts?.sku) {
      const s = window.MARKER_DATA.SKUS.find(x => x.id === opts.sku);
      if (s) setTimeout(() => setSheetSku(s), 100);
    }
  };

  const crumbsByPage = {
    pulse: ['Главная'],
    pnl: ['Финансы', 'P&L'],
    unit: ['Финансы', 'Юнит-экономика'],
    returns: ['Финансы', 'Возвраты'],
    products: ['Товары'],
    warehouse: ['Склады'],
    plan: ['План'],
    kit: ['UI Kit'],
  };
  const titlesByPage = {
    pulse: { title: 'Pulse Dashboard', sub: 'Сводка за период · ' + period },
    pnl: { title: 'P&L', sub: 'Прибыли и убытки · ' + period },
    unit: { title: 'Юнит-экономика', sub: 'Калькулятор сценариев' },
    returns: { title: 'Возвраты', sub: 'Анализ возвратов · ' + period },
    products: { title: 'Товары', sub: 'Каталог артикулов' },
    warehouse: { title: 'Склады', sub: 'Остатки и оборачиваемость' },
    plan: { title: 'План', sub: 'Цели и факт' },
    kit: { title: 'UI Kit', sub: 'Все компоненты в одном месте' },
  };
  const t = titlesByPage[page];

  const renderPage = () => {
    const props = { compare: tweaks.compare, mpFilter, density: tweaks.density, kpiVariant: tweaks.kpiVariant, onOpenSku: setSheetSku };
    switch (page) {
      case 'pulse': return <window.MarkerPulse {...props} />;
      case 'pnl': return <window.MarkerPnL {...props} />;
      case 'unit': return <window.MarkerUnit {...props} />;
      case 'products': return <window.MarkerProducts {...props} />;
      case 'kit': return <window.MarkerUIKit />;
      default: return <window.MarkerPnL {...props} />;
    }
  };

  return (
    <div className="app">
      <Sidebar active={page} onNav={onNav} collapsed={tweaks.sidebar === 'collapsed'} />
      <div className="page">
        <Topbar
          crumbs={crumbsByPage[page] || ['Marker']}
          onSearch={() => setCmdkOpen(true)}
          onTheme={() => setTweak('theme', tweaks.theme === 'light' ? 'dark' : 'light')}
          theme={tweaks.theme}
          onSidebarToggle={() => setTweak('sidebar', tweaks.sidebar === 'collapsed' ? 'expanded' : 'collapsed')}
          onSync={startSync}
        />
        <div className="page-header">
          <div>
            <h1 className="page-title">{t.title}</h1>
            <div className="page-subtitle">{t.sub}</div>
          </div>
          <div className="page-actions">
            <PeriodSelector value={period} onChange={setPeriod} compare={tweaks.compare} onCompare={(v) => setTweak('compare', v)} />
            <button className="btn btn-secondary" onClick={startSync}><MarkerIcon name="refresh" size={14}/>Sync</button>
            <button className="btn btn-primary"><MarkerIcon name="download" size={14}/>Export</button>
          </div>
        </div>
        <div className="filterbar">
          <span className="filterbar-label">Маркетплейсы</span>
          <MpFilter value={mpFilter} onChange={setMpFilter} />
          <div className="spacer"/>
          <span className="filterbar-label">Свежесть данных</span>
          <span className="badge badge-success" data-tip="WB: 11:24, Ozon: 11:10, YM: вчера 22:00"><span className="dot-status green"/>11:24 МСК</span>
        </div>
        <SyncBanner state={syncState} onClose={() => setSyncState(null)} />
        <div className="page-content">
          {renderPage()}
        </div>
      </div>

      <Sheet open={!!sheetSku} onClose={() => setSheetSku(null)}>
        <window.MarkerSkuDetail sku={sheetSku} onClose={() => setSheetSku(null)} />
      </Sheet>

      <CmdK open={cmdkOpen} onClose={() => setCmdkOpen(false)} onNav={onNav} />

      {tweaksOpen && <TweaksUI tweaks={tweaks} setTweak={setTweak} onClose={() => setTweaksOpen(false)} />}
    </div>
  );
}

function TweaksUI({ tweaks, setTweak, onClose }) {
  return (
    <div style={{ position: 'fixed', bottom: 16, right: 16, width: 280, background: 'var(--color-bg-surface)', border: '1px solid var(--color-border-subtle)', borderRadius: 10, boxShadow: 'var(--shadow-lg)', zIndex: 100, padding: 14, display: 'flex', flexDirection: 'column', gap: 12 }}>
      <div className="row">
        <strong>Tweaks</strong><div className="spacer"/>
        <button className="icon-btn" onClick={() => { onClose(); try { window.parent.postMessage({ type: '__edit_mode_dismissed' }, '*'); } catch(e) {} }}><MarkerIcon name="x" size={14}/></button>
      </div>
      <div>
        <div className="field-label">Тема</div>
        <div style={{ display: 'flex', gap: 4 }}>
          {['light', 'dark'].map(v => <button key={v} className={`btn btn-sm ${tweaks.theme === v ? 'btn-secondary' : 'btn-ghost'}`} style={{ flex: 1 }} onClick={() => setTweak('theme', v)}>{v === 'light' ? 'Светлая' : 'Тёмная'}</button>)}
        </div>
      </div>
      <div>
        <div className="field-label">Плотность</div>
        <div style={{ display: 'flex', gap: 4 }}>
          {[['compact','Компакт'],['standard','Станд.'],['comfortable','Просто.']].map(([v, l]) =>
            <button key={v} className={`btn btn-sm ${tweaks.density === v ? 'btn-secondary' : 'btn-ghost'}`} style={{ flex: 1, padding: '0 6px' }} onClick={() => setTweak('density', v)}>{l}</button>)}
        </div>
      </div>
      <div>
        <div className="field-label">Sidebar</div>
        <div style={{ display: 'flex', gap: 4 }}>
          {['expanded','collapsed'].map(v => <button key={v} className={`btn btn-sm ${tweaks.sidebar === v ? 'btn-secondary' : 'btn-ghost'}`} style={{ flex: 1 }} onClick={() => setTweak('sidebar', v)}>{v === 'expanded' ? 'Развёрн.' : 'Свёрн.'}</button>)}
        </div>
      </div>
      <div>
        <div className="field-label">Compare mode</div>
        <label className="row" style={{ cursor: 'pointer' }}>
          <input type="checkbox" checked={tweaks.compare} onChange={e => setTweak('compare', e.target.checked)} style={{ accentColor: 'var(--color-accent-interactive)' }} />
          <span style={{ fontSize: 13 }}>Сравнивать с предыдущим</span>
        </label>
      </div>
      <div>
        <div className="field-label">KPI карточки</div>
        <div style={{ display: 'flex', gap: 4 }}>
          {[['plain','Без графика'],['spark','Со sparkline']].map(([v, l]) =>
            <button key={v} className={`btn btn-sm ${tweaks.kpiVariant === v ? 'btn-secondary' : 'btn-ghost'}`} style={{ flex: 1, padding: '0 6px' }} onClick={() => setTweak('kpiVariant', v)}>{l}</button>)}
        </div>
      </div>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<App />);
