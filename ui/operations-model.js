(function () {
  'use strict';

  function append(query, key, value) {
    if (value !== null && value !== undefined && value !== '') query.append(key, String(value));
  }

  function documentQuery(filters) {
    filters = filters || {};
    var query = new URLSearchParams();
    append(query, 'q', filters.q);
    append(query, 'provider', filters.provider);
    append(query, 'ticker', filters.ticker);
    (filters.statuses || (filters.status ? [filters.status] : [])).forEach(function (status) { append(query, 'status', status); });
    append(query, 'from', filters.from);
    append(query, 'to', filters.to);
    if (filters.dueOnly) query.set('dueOnly', 'true');
    append(query, 'runId', filters.runId);
    append(query, 'ingestionRunId', filters.ingestionRunId);
    query.set('limit', String(filters.limit || 25));
    append(query, 'cursor', filters.cursor);
    return query;
  }

  function modelQuery(filters, range) {
    filters = filters || {};
    var query = new URLSearchParams();
    append(query, 'range', range);
    append(query, 'provider', filters.provider);
    append(query, 'operation', filters.operation);
    append(query, 'model', filters.model);
    if (filters.success !== null && filters.success !== undefined && filters.success !== '') query.set('success', String(filters.success));
    append(query, 'documentId', filters.documentId);
    append(query, 'attemptId', filters.attemptId);
    append(query, 'runId', filters.runId);
    query.set('limit', String(filters.limit || 25));
    append(query, 'cursor', filters.cursor);
    return query;
  }

  function mergePage(existing, incoming) {
    existing = existing || { items: [] };
    incoming = incoming || { items: [] };
    var seen = new Set();
    var items = existing.items
      ? existing.items.slice(0, 0)
      : (incoming.items || []).slice(0, 0);
    (existing.items || []).concat(incoming.items || []).forEach(function (item) {
      if (item.id !== null && item.id !== undefined) {
        if (seen.has(item.id)) return;
        seen.add(item.id);
      }
      items.push(item);
    });
    return Object.assign({}, incoming, { items: items });
  }

  function utcThroughDate(dateString) {
    if (typeof dateString !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(dateString)) return null;
    var date = new Date(dateString + 'T00:00:00.000Z');
    if (Number.isNaN(date.getTime()) || date.toISOString().slice(0, 10) !== dateString) return null;
    date.setUTCDate(date.getUTCDate() + 1);
    return date.toISOString();
  }

  function formatKnownCost(value, known, total) {
    if (value === null || value === undefined || !Number.isFinite(Number(value)) || Number(known) <= 0) return 'Unknown';
    var cost = '$' + Number(value).toFixed(6);
    if (Number(known) < Number(total)) return cost + ' · partial (' + known + '/' + total + ' calls)';
    return cost;
  }

  function localRoute(view, entries) {
    var query = new URLSearchParams();
    query.set('view', view);
    entries.forEach(function (entry) {
      if (Array.isArray(entry[1])) entry[1].forEach(function (value) { append(query, entry[0], value); });
      else append(query, entry[0], entry[1]);
    });
    return '?' + query.toString();
  }

  function signalRoute(signal) {
    if (!signal || typeof signal.code !== 'string') return null;
    switch (signal.code) {
      case 'DUE_DOCUMENTS':
        return localRoute('documents', [
          ['status', ['PENDING', 'RETRYABLE_ERROR']], ['dueOnly', 'true'], ['documentId', signal.documentId],
        ]);
      case 'TERMINAL_DOCUMENTS':
        return localRoute('documents', [['status', 'TERMINAL_ERROR']]);
      case 'PROCESSING_STALLED':
        return localRoute('documents', [['status', 'PROCESSING'], ['documentId', signal.documentId], ['runId', signal.runId]]);
      case 'AUTHENTICATION_FAILURE':
        if (signal.provider === 'openai') return localRoute('models', [['provider', 'openai'], ['success', 'false']]);
        if (signal.provider === 'polygon' || signal.provider === 'finnhub') {
          return localRoute('pipeline', [['ingestionProvider', signal.provider], ['ingestionStatus', 'FAILED']]);
        }
        return null;
      case 'INGESTION_OVERDUE':
        return localRoute('pipeline', [['kind', 'PIPELINE']]);
      case 'SNAPSHOTS_OVERDUE':
        return localRoute('pipeline', [['kind', 'DAILY_SNAPSHOTS']]);
      default:
        return null;
    }
  }

  window.CatalystOperationsModel = Object.freeze({ documentQuery: documentQuery, modelQuery: modelQuery,
    mergePage: mergePage, utcThroughDate: utcThroughDate, formatKnownCost: formatKnownCost, signalRoute: signalRoute });
})();
