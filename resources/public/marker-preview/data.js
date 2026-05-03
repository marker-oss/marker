// Mock data for Marker
const MARKETPLACES = ['wb', 'ozon', 'ym'];
const MP_LABEL = { wb: 'Wildberries', ozon: 'Ozon', ym: 'YM' };

// Deterministic pseudo-random
function seedRand(seed) {
  let s = seed;
  return () => { s = (s * 9301 + 49297) % 233280; return s / 233280; };
}

// 30-day daily series
function genSeries(seed, base, vol) {
  const r = seedRand(seed);
  const out = [];
  let v = base;
  for (let i = 0; i < 30; i++) {
    v = v + (r() - 0.45) * vol;
    out.push(Math.max(base * 0.4, v));
  }
  return out;
}

const REVENUE_SERIES = genSeries(1, 280000, 60000);
const PROFIT_SERIES  = genSeries(2, 78000,  22000);
const ORDERS_SERIES  = genSeries(3, 320,    80);
const ADS_SERIES     = genSeries(4, 32000,  9000);

// SKUs
function genSku(i) {
  const r = seedRand(i * 7 + 11);
  const mps = ['wb', 'ozon', 'ym'].filter((_, idx) => r() > [0.05, 0.4, 0.65][idx]);
  if (mps.length === 0) mps.push('wb');
  const revenue = Math.round((40000 + r() * 400000));
  const orders = Math.round(20 + r() * 240);
  const margin = 0.12 + r() * 0.32;
  const buyout = 0.55 + r() * 0.4;
  const stock = Math.round(r() * 800);
  const deltaPct = (r() - 0.45) * 80;
  const adsCost = Math.round(revenue * (0.06 + r() * 0.18));
  const roas = revenue / Math.max(1, adsCost);
  return {
    id: `SKU-${1200 + i}`,
    name: `Артикул ${1200 + i}`,
    mp: mps,
    revenue,
    orders,
    margin,
    buyout,
    stock,
    deltaPct,
    adsCost,
    roas,
    spark: genSeries(i * 13, revenue / 30, revenue / 80),
    plan: Math.round(revenue * (0.85 + r() * 0.4)),
  };
}
const SKUS = Array.from({ length: 32 }, (_, i) => genSku(i));

// P&L rows
const PNL_ROWS = [
  { key: 'revenue', label: 'Выручка', cur: 8420000, prev: 7510000, group: 'income' },
  { key: 'cogs',    label: 'Себестоимость', cur: -3380000, prev: -3010000, group: 'cost', muted: true },
  { key: 'gross',   label: 'Валовая прибыль', cur: 5040000, prev: 4500000, group: 'subtotal' },
  { key: 'commission', label: 'Комиссия МП', cur: -1430000, prev: -1280000, group: 'cost' },
  { key: 'logistics',  label: 'Логистика и FBO', cur: -680000, prev: -610000, group: 'cost' },
  { key: 'returns',    label: 'Возвраты', cur: -310000, prev: -290000, group: 'cost' },
  { key: 'ads',        label: 'Реклама', cur: -940000, prev: -780000, group: 'cost' },
  { key: 'opex',       label: 'Прочие расходы', cur: -210000, prev: -180000, group: 'cost' },
  { key: 'ebitda',     label: 'EBITDA', cur: 1470000, prev: 1360000, group: 'subtotal' },
  { key: 'taxes',      label: 'Налоги', cur: -310000, prev: -290000, group: 'cost' },
  { key: 'net',        label: 'Чистая прибыль', cur: 1160000, prev: 1070000, group: 'total' },
];

// Alerts
const ALERTS = [
  { kind: 'danger',  title: 'Маржа упала ниже 18%', body: 'SKU-1208 «Артикул 1208» — маржа 12,4% (было 24,1%) из-за роста CPC на WB.', cta: 'Открыть SKU' },
  { kind: 'warning', title: 'Остатки на исходе', body: '6 артикулов на FBO Ozon кончатся за ≤ 7 дней по текущей оборачиваемости.', cta: 'Перейти к остаткам' },
  { kind: 'info',    title: 'Sync YM не запускался 26 часов', body: 'Данные YM могут отставать. Последняя успешная — вчера в 22:00.', cta: 'Запустить sync' },
];

// Forecast
const FORECAST = {
  monthPlan: 12000000,
  monthFact: 8420000,
  monthPace: 8420000 / (3 / 30 + 22 / 30) * (30 / 22), // dummy
  projection: 11480000,
};

// Top movers / fallers
const TOP_MOVERS = SKUS.slice().sort((a, b) => b.deltaPct - a.deltaPct).slice(0, 5);
const TOP_FALLERS = SKUS.slice().sort((a, b) => a.deltaPct - b.deltaPct).slice(0, 5);

window.MARKER_DATA = {
  MARKETPLACES, MP_LABEL,
  REVENUE_SERIES, PROFIT_SERIES, ORDERS_SERIES, ADS_SERIES,
  SKUS, PNL_ROWS, ALERTS, FORECAST,
  TOP_MOVERS, TOP_FALLERS,
};
