# trinity-app

Operator shell: `TrinityApplication`, `/view` UI, security, upsell, wiring.

Требует торговое ядро на classpath (`../IMOEX-core`). Без него стартуйте `trinity-shell`.

```bash
# from IMOEX root, with sibling IMOEX-core
mvn -pl trinity-app -am spring-boot:run
```
