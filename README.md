# Marker

Аналитика маркетплейсов WB / Ozon / Yandex.Market — Clojure + SQLite + ClojureScript SPA.

> **Учебный проект, в активной разработке.**
> Marker — продуктовый бренд; в коде сохранён исторический namespace `analitica`.

> 🇬🇧 English version — [README.en.md](README.en.md)

---

## Возможности

- **Мульти-МП** — единый протокол для WB, Ozon и Yandex.Market
- **Синхронизация** — инкрементальная загрузка данных из API с контролем rate-limit
- **Продажи** — выручка, заказы, возвраты по дням / артикулам / категориям / складам
- **Финансы** — разбивка по артикулам: комиссии, логистика, хранение, штрафы, реклама
- **Unit-экономика** — прибыль на SKU с себестоимостью из 1С
- **P&L** — полный отчёт о прибылях и убытках
- **ABC-анализ** — классификация товаров по выручке / прибыли (правило 80/15/5)
- **Остатки и оборачиваемость** — уровни склада, риск out-of-stock
- **Возвраты** — динамика возвратов по артикулам
- **География** — продажи по регионам и городам
- **Тренды** — WoW и MoM сравнения
- **Аудит расчётов** — сверка с выпиской, KPI-базовая линия, гипотезы, регрессионные фикстуры
- **Экспорт Excel / CSV** — для всех отчётов из CLI и веб-интерфейса

---

## Архитектура с высоты птичьего полёта

```
WB API   ──►  wb/transform    ──►┐
Ozon API ──►  ozon/transform  ──►├──  SQLite  ──►  domain/*.clj  ──►  Web API / CLI / REPL
YM API   ──►  ym/transform    ──►┘
1C CSV   ──►  cost_price      ──────────────────►┘
```

Слои: **marketplace** (HTTP-клиенты + rate-limit) → **storage** (SQLite / next.jdbc) →
**domain** (бизнес-логика, MP-агностик) → **report** (таблицы + Excel/CSV) → **web** (API + UI).

**UI — re-frame ClojureScript SPA (`src/cljs/marker/`) — основной фронтенд;
server-rendered HTMX-страницы — legacy/переходный слой.**

Подробно — [docs/architecture.md](docs/architecture.md).

---

## Быстрый старт

### Требования

- Java 11+
- [Clojure CLI](https://clojure.org/guides/install_clojure)
- Node.js 18+ (для SPA)
- Токен WB (Ozon / YM — опционально)

### 1. Конфигурация

```bash
cp config.example.edn config.edn
# Откройте config.edn и впишите API-токены
```

### 2. Бэкенд

```bash
clojure -M -m analitica.web.server
# http://localhost:3000
```

### 3. Фронтенд (SPA)

```bash
npm install
npx shadow-cljs watch app
# SPA доступна по http://localhost:3000/app
```

### 4. Первая синхронизация

```bash
clojure -M -m analitica.cli sync all -p last-30-days
```

---

## Использование

### CLI

```bash
# Синхронизация
clojure -M -m analitica.cli sync all -p last-30-days
clojure -M -m analitica.cli sync finance -f 2026-03-01 -t 2026-03-31
clojure -M -m analitica.cli status

# Отчёты
clojure -M -m analitica.cli report pnl  -p last-30-days
clojure -M -m analitica.cli report ue   -p last-30-days
clojure -M -m analitica.cli report sales -p last-7-days -e reports/sales.xlsx

# Аудит расчётов
clojure -M -m analitica.cli audit reconcile -m wb -p last-30-days --bank-sum 1500000
clojure -M -m analitica.cli audit kpi measure -m wb -f 2026-03-01 -t 2026-03-31
```

Полный справочник команд — `clojure -M -m analitica.cli --help` (а также `audit help`, `schema help`); интерактивное меню — `clojure -M -m analitica.cli menu`.

### REPL

```clojure
(require '[analitica.core :refer :all])
(start!)
(help)   ;; список всех команд
```

### Форматы периодов

| Ключевое слово  | Значение              |
|-----------------|-----------------------|
| `:today`        | Текущий день          |
| `:yesterday`    | Предыдущий день       |
| `:last-7-days`  | Последние 7 дней      |
| `:last-30-days` | Последние 30 дней     |
| `:this-week`    | Пн — сегодня          |
| `:this-month`   | 1-е числа — сегодня   |

Произвольный диапазон: `{:from "2026-03-01" :to "2026-03-31"}`

---

## Документация

| Файл | Содержание |
|------|-----------|
| [docs/architecture.md](docs/architecture.md) | Полная архитектура, слои, ключевые решения |
| [docs/dev-setup.md](docs/dev-setup.md) | Локальная разработка, REPL, SPA, тесты, линтер |
| [docs/data-dictionary.md](docs/data-dictionary.md) | Словарь полей всех отчётов |
| [docs/db-schema.md](docs/db-schema.md) | Схема базы данных SQLite |
| [docs/canonical-formulas.md](docs/canonical-formulas.md) | Канонические формулы unit-экономики и P&L |
| [docs/reconciliation.md](docs/reconciliation.md) | Сверка с ЛК маркетплейсов |
| [docs/deploy.md](docs/deploy.md) | Деплой (локальная сеть / сервер) |
| [docs/marker-api.md](docs/marker-api.md) | REST API веб-сервера |
| [docs/concept-crosswalk.md](docs/concept-crosswalk.md) | Маппинг терминов WB / Ozon / YM → canonical |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Как участвовать в разработке |
| [SECURITY.md](SECURITY.md) | Политика безопасности |

---

## Тестирование

```bash
# Clojure (интеграционные помечены ^:integration и пропускаются по умолчанию)
clojure -M:test

# ClojureScript
npx shadow-cljs compile test
```

Подробнее — [docs/dev-setup.md](docs/dev-setup.md).

---

## Лицензия

[MIT](LICENSE) © 2026 Marker project authors.
Вклад — [CONTRIBUTING.md](CONTRIBUTING.md).
