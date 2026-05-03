// Shared chrome: Sidebar, Topbar, Sheet, Modal, MP filter, Period selector, Sync banner, Cmd+K
const { useState, useEffect, useRef, useMemo } = React;
const { formatRub, formatInt, formatShort, formatPct, formatMul, formatDate, pluralRu, NBSP } = window.MarkerFmt;

// ============= Sidebar =============
const NAV = [
  { id: 'pulse', label: 'Главная (Pulse)', icon: 'pulse' },
  { id: 'finance', label: 'Финансы', icon: 'finance', children: [
    { id: 'pnl', label: 'P&L' },
    { id: 'unit', label: 'Юнит-экономика' },
    { id: 'returns', label: 'Возвраты' },
  ]},
  { id: 'products', label: 'Товары', icon: 'products', counter: '32' },
  { id: 'warehouse', label: 'Склады', icon: 'warehouse' },
  { id: 'plan', label: 'План', icon: 'target' },
  { id: 'kit', label: 'UI Kit', icon: 'sparkles' },
];

function Sidebar({ active, onNav, collapsed }) {
  const [openGroups, setOpenGroups] = useState(new Set(['finance']));
  const toggleGroup = (id) => {
    const s = new Set(openGroups);
    s.has(id) ? s.delete(id) : s.add(id);
    setOpenGroups(s);
  };
  return (
    <aside className="sidebar">
      <div className="sidebar-brand">
        <div className="brand-mark" />
        <div className="brand-name">Marker<span className="dot">.</span></div>
      </div>
      <nav className="sidebar-nav">
        {NAV.map(item => {
          const isActive = active === item.id || (item.children && item.children.some(c => c.id === active));
          if (item.children) {
            const open = openGroups.has(item.id);
            return (
              <div key={item.id}>
                <button className={`nav-item ${isActive ? 'active' : ''}`} onClick={() => toggleGroup(item.id)}>
                  <MarkerIcon name={item.icon} className="nav-icon" />
                  <span className="nav-label">{item.label}</span>
                  <MarkerIcon name="chevDown" size={12} style={{ marginLeft: 'auto', transform: open ? 'none' : 'rotate(-90deg)', transition: 'transform 150ms' }} />
                </button>
                {open && (
                  <div className="nav-children">
                    {item.children.map(c => (
                      <button key={c.id} className={`nav-item ${active === c.id ? 'active' : ''}`} onClick={() => onNav(c.id)}>
                        <span className="nav-label">{c.label}</span>
                      </button>
                    ))}
                  </div>
                )}
              </div>
            );
          }
          return (
            <button key={item.id} className={`nav-item ${active === item.id ? 'active' : ''}`} onClick={() => onNav(item.id)}>
              <MarkerIcon name={item.icon} className="nav-icon" />
              <span className="nav-label">{item.label}</span>
              {item.counter && <span className="nav-counter">{item.counter}</span>}
            </button>
          );
        })}
      </nav>
      <div className="sidebar-foot">
        <div style={{ fontSize: 11, color: 'var(--color-fg-muted)', textTransform: 'uppercase', letterSpacing: '.5px', fontWeight: 500 }}>
          Период
        </div>
        <button className="btn btn-secondary" style={{ justifyContent: 'space-between', width: '100%' }}>
          <span>Май 2026</span>
          <MarkerIcon name="chevDown" size={12} />
        </button>
      </div>
    </aside>
  );
}

// ============= Topbar =============
function Topbar({ crumbs, onSearch, onTheme, theme, onSidebarToggle, onSync }) {
  return (
    <div className="topbar">
      <button className="icon-btn" onClick={onSidebarToggle} title="Свернуть"><MarkerIcon name="panel" /></button>
      <div className="crumbs">
        {crumbs.map((c, i) => (
          <React.Fragment key={i}>
            {i > 0 && <span className="sep">/</span>}
            {i === crumbs.length - 1
              ? <span className="current">{c}</span>
              : <a href="#">{c}</a>}
          </React.Fragment>
        ))}
      </div>
      <div className="spacer" />
      <button className="search-trigger" onClick={onSearch}>
        <MarkerIcon name="search" size={14} />
        <span>Поиск артикула или раздела…</span>
        <kbd>⌘K</kbd>
      </button>
      <button className="icon-btn" onClick={onSync} title="Запустить sync"><MarkerIcon name="refresh" /></button>
      <button className="icon-btn" title="Уведомления"><MarkerIcon name="bell" /></button>
      <button className="icon-btn" onClick={onTheme} title="Тема">
        <MarkerIcon name={theme === 'dark' ? 'sun' : 'moon'} />
      </button>
      <div className="avatar">КМ</div>
    </div>
  );
}

// ============= MP Filter =============
function MpFilter({ value, onChange }) {
  const all = ['wb', 'ozon', 'ym'];
  const allSelected = value.length === 3;
  const toggle = (mp) => {
    if (value.includes(mp)) onChange(value.filter(m => m !== mp));
    else onChange([...value, mp]);
  };
  return (
    <div style={{ display: 'flex', gap: 6 }}>
      <button
        className={`chip ${allSelected ? 'is-active' : ''}`}
        onClick={() => onChange(allSelected ? [] : all)}
      >Все</button>
      {all.map(mp => (
        <button
          key={mp}
          className={`chip chip-mp-${mp} ${value.includes(mp) ? '' : 'off'}`}
          onClick={() => toggle(mp)}
        >
          <span className={`mp-dot ${mp}`} style={{ width: 14, height: 14, fontSize: 8 }}>{mp[0].toUpperCase()}</span>
          {MP_LABEL_LOCAL[mp]}
        </button>
      ))}
    </div>
  );
}
const MP_LABEL_LOCAL = { wb: 'WB', ozon: 'Ozon', ym: 'YM' };

// ============= Period selector =============
const PERIOD_PRESETS = [
  'Сегодня', 'Вчера', 'Последние 7 дней', 'Последние 30 дней',
  'Этот месяц', 'Прошлый месяц', 'Этот квартал', 'Этот год',
];

function PeriodSelector({ value, onChange, compare, onCompare }) {
  const [open, setOpen] = useState(false);
  const ref = useRef(null);
  useEffect(() => {
    const close = (e) => { if (ref.current && !ref.current.contains(e.target)) setOpen(false); };
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, []);
  return (
    <div ref={ref} style={{ position: 'relative', display: 'flex', gap: 6, alignItems: 'center' }}>
      <button className="btn btn-secondary" onClick={() => setOpen(!open)}>
        <MarkerIcon name="calendar" size={14} />
        {value}
        <MarkerIcon name="chevDown" size={12} />
      </button>
      <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, color: 'var(--color-fg-secondary)', cursor: 'pointer' }}>
        <input type="checkbox" checked={compare} onChange={e => onCompare(e.target.checked)} style={{ accentColor: 'var(--color-accent-interactive)' }} />
        Сравнить с пред.
      </label>
      {open && (
        <div className="popover" style={{ top: '100%', marginTop: 4, left: 0, minWidth: 220 }}>
          {PERIOD_PRESETS.map(p => (
            <button key={p} className={`popover-item ${p === value ? 'is-active' : ''}`} onClick={() => { onChange(p); setOpen(false); }}>
              {p === value && <MarkerIcon name="check" size={12} />}
              <span style={{ marginLeft: p === value ? 0 : 18 }}>{p}</span>
            </button>
          ))}
          <div className="popover-divider" />
          <button className="popover-item">
            <span style={{ marginLeft: 18 }}>Свой диапазон…</span>
          </button>
        </div>
      )}
    </div>
  );
}

// ============= Sheet =============
function Sheet({ open, onClose, children }) {
  useEffect(() => {
    const onKey = (e) => { if (e.key === 'Escape') onClose(); };
    if (open) document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [open, onClose]);
  return (
    <>
      <div className={`sheet-backdrop ${open ? 'open' : ''}`} onClick={onClose} />
      <div className={`sheet ${open ? 'open' : ''}`}>{open && children}</div>
    </>
  );
}

// ============= Modal =============
function Modal({ open, onClose, children }) {
  useEffect(() => {
    const onKey = (e) => { if (e.key === 'Escape') onClose(); };
    if (open) document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [open, onClose]);
  return (
    <div className={`modal-backdrop ${open ? 'open' : ''}`} onClick={onClose}>
      <div className="modal" onClick={e => e.stopPropagation()}>{open && children}</div>
    </div>
  );
}

// ============= Cmd+K Search =============
function CmdK({ open, onClose, onNav }) {
  const [q, setQ] = useState('');
  const inputRef = useRef(null);
  useEffect(() => { if (open) setTimeout(() => inputRef.current?.focus(), 50); }, [open]);
  const SKUS = window.MARKER_DATA.SKUS;
  const results = useMemo(() => {
    const ql = q.toLowerCase();
    const skuMatches = SKUS.filter(s => s.id.toLowerCase().includes(ql) || s.name.toLowerCase().includes(ql)).slice(0, 6);
    const navMatches = NAV.flatMap(n => n.children ? [n, ...n.children] : [n])
      .filter(n => n.label.toLowerCase().includes(ql) && ql.length > 0).slice(0, 4);
    return { skuMatches, navMatches };
  }, [q]);
  return (
    <Modal open={open} onClose={onClose}>
      <div className="cmdk">
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '0 12px', borderBottom: '1px solid var(--color-border-subtle)' }}>
          <MarkerIcon name="search" size={16} style={{ color: 'var(--color-fg-muted)' }} />
          <input ref={inputRef} className="cmdk-input" placeholder="Поиск артикула, страницы, действия…" value={q} onChange={e => setQ(e.target.value)} />
          <kbd className="kbd">esc</kbd>
        </div>
        <div className="cmdk-results">
          {results.navMatches.length > 0 && (
            <>
              <div className="cmdk-section-title">Навигация</div>
              {results.navMatches.map(n => (
                <div key={n.id} className="cmdk-item" onClick={() => { onNav(n.id); onClose(); }}>
                  <MarkerIcon name={n.icon || 'arrowRight'} size={14} style={{ color: 'var(--color-fg-muted)' }} />
                  {n.label}
                </div>
              ))}
            </>
          )}
          <div className="cmdk-section-title">Артикулы</div>
          {(q ? results.skuMatches : SKUS.slice(0, 6)).map(s => (
            <div key={s.id} className="cmdk-item" onClick={() => { onNav('products', { sku: s.id }); onClose(); }}>
              <span className="mono" style={{ color: 'var(--color-fg-muted)', minWidth: 70 }}>{s.id}</span>
              <span style={{ flex: 1 }}>{s.name}</span>
              <div style={{ display: 'flex', gap: 4 }}>
                {s.mp.map(m => <span key={m} className={`mp-dot ${m}`}>{m[0].toUpperCase()}</span>)}
              </div>
            </div>
          ))}
        </div>
        <div className="cmdk-foot">
          <span><kbd className="kbd">↑↓</kbd> навигация</span>
          <span><kbd className="kbd">↵</kbd> открыть</span>
          <span><kbd className="kbd">esc</kbd> закрыть</span>
        </div>
      </div>
    </Modal>
  );
}

// ============= Sync Banner =============
function SyncBanner({ state, onClose }) {
  if (!state) return null;
  if (state.kind === 'success') {
    return (
      <div className="sync-banner success">
        <MarkerIcon name="check" size={16} />
        <span><strong>Готово.</strong> Данные обновлены {state.time}</span>
        <div className="spacer" />
        <button className="icon-btn" onClick={onClose} style={{ color: 'inherit' }}><MarkerIcon name="x" size={14} /></button>
      </div>
    );
  }
  return (
    <div className="sync-banner">
      <div className="sync-spin" />
      <span><strong>Синхронизация {state.section}</strong> — {state.elapsed}</span>
      <div className="sync-progress"><div style={{ width: `${state.progress}%` }} /></div>
      <span className="mono" style={{ minWidth: 36, textAlign: 'right' }}>{state.progress}%</span>
    </div>
  );
}

// ============= Sparkline =============
function Sparkline({ data, width = 80, height = 28, color }) {
  const max = Math.max(...data), min = Math.min(...data);
  const range = max - min || 1;
  const trend = data[data.length - 1] >= data[0];
  const c = color || (trend ? 'var(--color-delta-positive)' : 'var(--color-delta-negative)');
  const pts = data.map((v, i) => {
    const x = (i / (data.length - 1)) * (width - 4) + 2;
    const y = height - 2 - ((v - min) / range) * (height - 4);
    return `${x},${y}`;
  }).join(' ');
  const last = pts.split(' ').slice(-1)[0].split(',');
  return (
    <svg width={width} height={height} style={{ display: 'block' }}>
      <polyline points={pts} fill="none" stroke={c} strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
      <circle cx={last[0]} cy={last[1]} r="2.5" fill={c} />
    </svg>
  );
}

// ============= Delta =============
function Delta({ pct, inverted = false, suffix = '' }) {
  if (pct == null || isNaN(pct)) return <span className="delta flat">—</span>;
  const isUp = pct > 0.05;
  const isDown = pct < -0.05;
  const dir = isUp ? 'up' : (isDown ? 'down' : 'flat');
  const cls = inverted ? (isUp ? 'down' : (isDown ? 'up' : 'flat')) : dir;
  const arrow = isUp ? '↑' : isDown ? '↓' : '→';
  return (
    <span className={`delta ${cls}`}>
      {arrow} {Math.abs(pct).toFixed(1).replace('.', ',')}%{suffix}
    </span>
  );
}

// ============= MP badge inline =============
function MpBadge({ mp }) {
  return <span className={`mp-dot ${mp}`}>{mp === 'wb' ? 'W' : mp === 'ozon' ? 'O' : 'Y'}</span>;
}

Object.assign(window, {
  Sidebar, Topbar, MpFilter, PeriodSelector, Sheet, Modal, CmdK, SyncBanner,
  Sparkline, Delta, MpBadge, NAV
});
