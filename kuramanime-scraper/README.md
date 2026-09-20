# Kuramanime Token Scraper

Script Node.js (zero dependency, Node ≥ 18) untuk extract **Authorization Bearer** dari `leviathan.js`. Cocok dijalankan lokal atau di **GitHub Actions**.

## Lokal

```bash
# default mainUrl
node scrape-token.js

# custom domain
MAIN_URL=https://v20.kuramanime.ing node scrape-token.js

# JSON + file
node scrape-token.js --json --out token.json
```

Output (default): token saja di stdout.

```
kJuHHkaqcBFXiGMHQf6bJw8YAyDcwGD8Ur
```

## GitHub Actions (auto update provider)

Workflow: `.github/workflows/scrape-token.yml` (taruh di root repo, folder ini juga di root repo).

Trigger: **workflow_dispatch** (manual saja, tidak ada jadwal otomatis). Jalankan dari tab Actions → Update Kuramanime Token → Run workflow.

Alurnya dua job:

1. `scrape` (permission read-only): jalankan `scrape-token.js`, upload `token.json` sebagai artifact `kuramanime-token`.
2. `update` (permission write): jalankan `update-provider.js token.json`. Kalau token beda dari yang ada di
   `var authorization: String? = "..."` pada `KuramanimeProvider.kt`, token diganti, `version` di
   `KuramanimeProvider/build.gradle.kts` naik 1, lalu di-commit dan di-push. Kalau `.github/workflows/build.yml`
   ada, workflow build dipanggil otomatis (push dari `GITHUB_TOKEN` tidak memicu workflow lain).

Kalau token tidak berubah, tidak ada commit dan versi tidak naik.

`update-provider.js` menolak token yang formatnya aneh (hanya `A-Z a-z 0-9 _ - . ~ + / =`, 16-128 karakter), supaya
isi dari luar tidak bisa disisipkan ke source Kotlin.

Jalankan manual di lokal:

```bash
node kuramanime-scraper/scrape-token.js --json --out token.json
node kuramanime-scraper/update-provider.js token.json
```

### Optional repo variables

| Name | Default | Keterangan |
|------|---------|------------|
| `MAIN_URL` | `mainUrl` di `KuramanimeProvider.kt` | Base URL site |

### Env `update-provider.js`

| Variable | Keterangan |
|----------|------------|
| `KURAMANIME_TOKEN` | Token langsung, kalau tidak pakai `token.json` |
| `PROVIDER_FILE` | Path `KuramanimeProvider.kt` kalau lokasinya beda |
| `GRADLE_FILE` | Path `build.gradle.kts` kalau lokasinya beda |

## Env

| Variable | Keterangan |
|----------|------------|
| `MAIN_URL` | Base URL Kuramanime |
| `LEVIATHAN_URL` | Langsung pakai URL JS (skip parse homepage) |
| `USER_AGENT` | Custom UA |
| `GITHUB_OUTPUT` | Otomatis di Actions — menulis `token` & `authorization` |
| `WRITE_ENV=1` | Tulis `token.env` |

## Catatan

- Kalau kena **Cloudflare**, fetch JS akan gagal (response HTML). Jalankan dari runner yang tidak di-block, atau set `LEVIATHAN_URL` dari mirror/cache.
- Token di dalam `leviathan.js` saat ini **statis**; script tetap extract dinamis supaya ikut update jika admin ganti isi file.
