// what-if.js — UE what-if calculator.
// CANONICAL Clojure formula lives in src/analitica/domain/unit_economics.clj.
// Keep this file in lockstep — see test/analitica/web/what_if_sync_test.clj
// for the alarm bell that fires if constants drift.
//
// Mounting target: an element with id "what-if-card" containing inputs
// labeled by data-whatif-input="<key>" and outputs labeled by
// data-whatif-output="net" / "romi".

(function () {
  'use strict';

  // Mirrors the canonical net-profit formula. Input units:
  //   price          ₽ (sale price)
  //   buyoutPct      0..1 fraction
  //   commissionPct  0..1 fraction
  //   logisticsRub   ₽ per unit
  //   cogs           ₽ per unit
  //   cpcRub         ₽ per click
  //   cr             0..1 conversion rate (clicks → buyout)
  var netProfit = function (p) {
    var rev    = p.price * p.buyoutPct;
    var comm   = rev * p.commissionPct;
    var logi   = p.logisticsRub;
    var cogs   = p.cogs;
    var adCost = p.cpcRub / Math.max(p.cr, 0.0001);
    return rev - comm - logi - cogs - adCost;
  };

  var romi = function (p) {
    var net    = netProfit(p);
    var adCost = p.cpcRub / Math.max(p.cr, 0.0001);
    return adCost > 0 ? net / adCost : null;
  }

  function fmtRub(v) {
    if (v === null || isNaN(v)) return '—';
    return v.toLocaleString('ru-RU', { maximumFractionDigits: 0 }) + ' ₽';
  }

  function fmtMult(v) {
    if (v === null || isNaN(v)) return '—';
    return v.toFixed(2);
  }

  function readInputs(card) {
    var get = function (key) {
      var el = card.querySelector('[data-whatif-input="' + key + '"]');
      if (!el) return NaN;
      return parseFloat(el.value);
    };
    return {
      price:         get('price'),
      buyoutPct:     get('buyoutPct'),
      commissionPct: get('commissionPct'),
      logisticsRub:  get('logisticsRub'),
      cogs:          get('cogs'),
      cpcRub:        get('cpcRub'),
      cr:            get('cr')
    };
  }

  function refresh(card) {
    var p = readInputs(card);
    var net = netProfit(p);
    var rmi = romi(p);
    var nEl = card.querySelector('[data-whatif-output="net"]');
    var rEl = card.querySelector('[data-whatif-output="romi"]');
    if (nEl) nEl.textContent = fmtRub(net);
    if (rEl) rEl.textContent = fmtMult(rmi);

    // Update slider labels.
    card.querySelectorAll('[data-whatif-input]').forEach(function (el) {
      var key = el.dataset.whatifInput;
      var lbl = card.querySelector('[data-whatif-label="' + key + '"]');
      if (lbl) lbl.textContent = el.value;
    });
  }

  function attach(card) {
    card.querySelectorAll('[data-whatif-input]').forEach(function (el) {
      el.addEventListener('input', function () { refresh(card); });
    });
    refresh(card);  // initial render
  }

  document.addEventListener('DOMContentLoaded', function () {
    var card = document.getElementById('what-if-card');
    if (card) attach(card);
  });
})();
