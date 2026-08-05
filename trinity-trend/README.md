# trinity-trend

Strategy 2 — **trend trading robot** playbooks.

## Playbook #1: `levels-profile-br-m5`

«Уровни + профиль рынка» (BR, M5) — Exclusive checklist + hardenings:

- Market profile VAP proxy on bounce candles → merge HVN → zone **15–20 pts**
- Bounce / break+retest modes
- Limit grid `MODERATE` (2-2-2) or `AGGRESSIVE` ((N-2)-1-1)
- Sizing: `min(GO capacity, maxRiskPct equity)`
- Staged exits: TP1 → BE → runner (bounce ×2 / retest ×1.5 stop)

## Enable

```yaml
imoex:
  strategies:
    trend:
      enabled: true
      auto-execution: false   # SIGNAL_ONLY: ticker + BUY/SELL; no journal/orders
      live-execution: false   # FORTS live only with auto-execution=true
      playbook: levels-profile-br-m5
      grid: MODERATE
      max-risk-pct-equity: 1.0
```

SPI: `TrendPlaybook`, `TrendRobotEngine`, `TrendSignal`, `TrendResearchService`.
Execution bridge lives in `trinity-app` (`TrendExecutionBridge`).
API: `GET /api/trend/signal` — compact ticker+side for manual operators.

## Playbook switching

- **One playbook armed at a time** (no two BR grids on the same instrument).
- `DefaultTrendRegimeSelector`: TREND-like regime (or ADX ≥ 25) → pick playbook;
  prefer `imoex.strategies.trend.playbook` id, else first registered bean.
- SIDEWAYS / low ADX → selector returns empty (pairs own that book).
- Cross-strategy capital (pairs vs trend vs arb) stays at app `CapitalAllocator` / ADX gates — not inside a playbook.
- Later: score several playbooks (instrument / HTF / session), still **≤1** plan per bar.
