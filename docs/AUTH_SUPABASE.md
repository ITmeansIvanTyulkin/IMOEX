# Shared auth: TRINITY cabinet + IMOEX dashboard (Supabase)

Один email/пароль для [trinity-landing](https://github.com/ITmeansIvanTyulkin/trinity-landing) кабинета и операторского `/view`.

## Idea

- **IdP:** Supabase Auth (email confirm + password)
- **Cabinet:** supabase-js (anon key)
- **IMOEX API:** Bearer JWT (HS256, Project JWT Secret) или HTTP Basic (локальный operator fallback)
- **`imoex.run.unlock`:** boot guard процесса — не путать с логином пользователя

## Enable in IMOEX

`application-local.yml` (не в git):

```yaml
imoex:
  auth:
    enabled: true
    password: "…local basic fallback…"
    supabase:
      enabled: true
      url: https://YOUR_PROJECT.supabase.co
      anon-key: "YOUR_ANON_KEY"       # public; for operator UI login
      # optional: legacy HS256 secret (новые проекты подписывают ES256 через JWKS)
      jwt-secret: "YOUR_JWT_SECRET"
```

Или env: `SUPABASE_URL`, `SUPABASE_JWT_SECRET`, `SUPABASE_ANON_KEY`.

Бэкенд проверяет Bearer через JWKS (`{url}/auth/v1/.well-known/jwks.json`, ES256) и опционально legacy HS256.

Проверка: `GET http://localhost:8080/api/auth/mode`

## Operator UI

При `supabase.enabled=true` форма на `/view` принимает **email + password**, логинится в Supabase, кладёт access token в `localStorage` (`trinity.supabase.access_token` — тот же ключ, что кабинет) и шлёт `Authorization: Bearer …` на POST `/api/**`.

Basic (`imoex` / local password) остаётся запасным контуром.

## Cabinet setup

См. в репо лендинга: `docs/SUPABASE_SETUP.md` + `supabase/profiles.sql`.
