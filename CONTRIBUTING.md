# Участие в разработке

## Окружение

Инструкция по развёртыванию: [docs/dev-setup.md](docs/dev-setup.md).

## Тесты перед PR

Убедитесь, что оба тест-сюита проходят:

```bash
clojure -M:test
npx shadow-cljs compile test
```

## Ветки

| Префикс | Назначение |
|---|---|
| `feature/<topic>` | новая функциональность |
| `fix/<topic>` | исправление ошибки |
| `docs/<topic>` | документация |
| `chore/<topic>` | инфраструктура, зависимости, прочее |

## Коммиты

Проект использует [Conventional Commits](https://www.conventionalcommits.org/):

```
feat:   новая функциональность
fix:    исправление ошибки
docs:   документация
chore:  инфраструктура / зависимости
```

Примеры из истории репозитория:

```
feat(pulse): add margin sparkline to dashboard
fix(unit-econ): rename "продано:" → "Реализовано:"
docs: add dev-setup guide
chore(release): switch license AGPL-3.0 → MIT
```

## Pull Request

- Небольшие PR — легче ревьюить.
- Тесты зелёные перед открытием.
- В описании — что изменилось и зачем.

## Архитектура

Для понимания устройства системы: [docs/architecture.md](docs/architecture.md).

## Безопасность

Инструкции по раскрытию уязвимостей: [SECURITY.md](SECURITY.md).
