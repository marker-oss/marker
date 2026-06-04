# Настройка окружения разработки

## Требования

- **Java 11+** (OpenJDK или совместимый JDK)
- **Clojure CLI** — установка: https://clojure.org/guides/install_clojure
- **Node.js 18+** — необходим для сборки ClojureScript SPA

## Конфигурация

Скопируйте шаблон конфигурации и заполните токены маркетплейсов:

```bash
cp config.example.edn config.edn
```

Откройте `config.edn` и вставьте API-токены для Wildberries, Ozon и/или Яндекс Маркета.

> Оба файла `.env` и `config.edn` перечислены в `.gitignore` — не коммитьте их.

## Бэкенд

### REPL (интерактивная разработка)

```bash
clojure -M:dev:repl
```

После запуска nREPL-сервера подключитесь из редактора или из той же сессии:

```clojure
(require '[analitica.core :refer :all])
(start!)   ; инициализирует БД, кеши, клиентов маркетплейсов
(help)     ; список доступных команд
```

### Веб-сервер

```bash
clojure -M -m analitica.web.server
```

Сервер поднимается на http://localhost:3000.

## Фронтенд (ClojureScript SPA)

Фронтенд — re-frame SPA (Marker UI), собирается shadow-cljs.

### Установка зависимостей

```bash
npm install
```

### Режим разработки (hot reload)

```bash
npx shadow-cljs watch app
```

SPA доступна на http://localhost:3000/app (обслуживается бэкендом через `wrap-resource`).
shadow-cljs также поднимает dev-HTTP на порту 8081 для статики.

### Продакшн-сборка

```bash
npx shadow-cljs release app
```

Скомпилированные файлы попадают в `resources/public/js/cljs-out/` и раздаются бэкендом автоматически.

Те же команды доступны через npm:

```bash
npm run watch    # эквивалент npx shadow-cljs watch app
npm run release  # эквивалент npx shadow-cljs release app
```

## Тесты

### Clojure (backend)

Запуск unit-тестов (интеграционные пропускаются по умолчанию):

```bash
clojure -M:test
```

Запуск с интеграционными тестами (требует SQLite-базу):

```bash
ANALITICA_DB=test-analitica.db clojure -M:test --no-skip-meta :integration
```

Тест-раннер — [Kaocha](https://github.com/lambdaisland/kaocha); конфигурация в `tests.edn`.

### ClojureScript (frontend)

```bash
npx shadow-cljs compile test
```

Запускает тесты под Node.js (сборка `:test` в `shadow-cljs.edn`, цель `:node-test`).

## Линтинг

### clj-kondo

```bash
clj-kondo --lint src test
```

Конфигурация находится в `.clj-kondo/`.

### clojure-lsp

Используется редакторами (VS Code, Emacs, IntelliJ) автоматически. Конфигурация в `.lsp/`.
