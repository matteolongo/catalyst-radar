(function () {
  'use strict';

  /** The server accepts at most this many tickers in one discovery read. */
  var LIMIT = 50;
  var TICKER = /^[A-Z0-9][A-Z0-9.-]{0,14}$/;

  /** Tolerates missing or corrupted storage: anything unreadable means "following nobody". */
  function parse(raw) {
    var value;
    try { value = JSON.parse(raw); } catch (_) { return []; }
    if (!Array.isArray(value)) return [];
    var seen = [];
    value.forEach(function (ticker) {
      var normalized = typeof ticker === 'string' ? ticker.trim().toUpperCase() : '';
      if (TICKER.test(normalized) && !seen.includes(normalized) && seen.length < LIMIT) seen.push(normalized);
    });
    return seen.sort();
  }

  /** Returns the new list, or null when following one more would exceed the limit. */
  function toggle(list, ticker) {
    var normalized = String(ticker || '').trim().toUpperCase();
    if (!TICKER.test(normalized)) return list.slice();
    if (list.includes(normalized)) return list.filter(function (item) { return item !== normalized; });
    if (list.length >= LIMIT) return null;
    return list.concat([normalized]).sort();
  }

  window.CatalystFollowing = Object.freeze({ parse: parse, toggle: toggle, LIMIT: LIMIT });
})();
