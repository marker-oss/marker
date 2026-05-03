// Formatters for Russian locale
(function() {
const NBSP = '\u00A0';

function formatRub(n, withSign = false) {
  if (n == null || isNaN(n)) return '—';
  const sign = n < 0 ? '−' : (withSign && n > 0 ? '+' : '');
  const abs = Math.abs(Math.round(n));
  const s = abs.toString().replace(/\B(?=(\d{3})+(?!\d))/g, NBSP);
  return `${sign}${s}${NBSP}₽`;
}

function formatInt(n) {
  if (n == null || isNaN(n)) return '—';
  return Math.round(n).toString().replace(/\B(?=(\d{3})+(?!\d))/g, NBSP);
}

function formatShort(n) {
  if (n == null || isNaN(n)) return '—';
  const abs = Math.abs(n);
  if (abs >= 1_000_000) return (n / 1_000_000).toFixed(1).replace('.', ',') + 'M';
  if (abs >= 1_000) return (n / 1_000).toFixed(1).replace('.', ',') + 'K';
  return Math.round(n).toString();
}

function formatPct(n, digits = 1) {
  if (n == null || isNaN(n)) return '—';
  return n.toFixed(digits).replace('.', ',') + '%';
}

function formatMul(n) {
  if (n == null || isNaN(n)) return '—';
  return n.toFixed(1).replace('.', ',') + '×';
}

function formatDate(d) {
  const dd = String(d.getDate()).padStart(2, '0');
  const mm = String(d.getMonth() + 1).padStart(2, '0');
  return `${dd}.${mm}.${d.getFullYear()}`;
}

function pluralRu(n, one, few, many) {
  const a = Math.abs(n) % 100;
  const a1 = a % 10;
  if (a >= 11 && a <= 14) return many;
  if (a1 === 1) return one;
  if (a1 >= 2 && a1 <= 4) return few;
  return many;
}

window.MarkerFmt = { formatRub, formatInt, formatShort, formatPct, formatMul, formatDate, pluralRu, NBSP };
})();
