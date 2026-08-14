# trinity-marketdata

Broker marketplace feed (T-Invest): live trades tape + order book.

**Dependency rule:** strategies may depend on `trinity-marketdata`; marketdata must **not** depend on pairs/trend/arb.

## Sources

| Data | Source |
|------|--------|
| Live trades | MarketDataStream → buffer + `data/broker-tape/tape-*.jsonl` |
| Live DOM | Stream + unary `GetOrderBook` at **depth 50** (API max: 10/20/30/40/50) |
| Hist trades | Archive first; else `GetLastTrades` (~last hour) |
| Hist DOM | **Our** `data/broker-tape/dom-*.jsonl` (broker has no hist DOM API) |
| ISS / M1 | not used |

## Daily accumulate

Keep app / recorder running during session:

```bash
mvn -pl trinity-marketdata -q exec:java \
  -Dexec.mainClass=com.moex.trinity.marketdata.BrokerTapeRecorder \
  -Dexec.args="BRU6"
```

## Config

```yaml
imoex:
  marketdata:
    enabled: true
    provider: T_INVEST
    orderbook-depth: 50
    sandbox: false
    auto-resolve-instrument: BRU6
```

Token: `imoex.broker.token` / `T_INVEST_TOKEN` / `data/broker-ui-settings.json`.
