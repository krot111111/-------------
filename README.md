# poputchik (СтудТакси)

## Запуск

Реальные пароли не хранятся в коде - задавай их через переменные окружения перед запуском:

```
DB_URL=jdbc:postgresql://localhost:5432/uniride
DB_USER=...
DB_PASSWORD=...
ADMIN_USERNAME=...
ADMIN_PASSWORD=...
```

Без этих переменных сервер запустится с заглушками (`postgres`/`changeme`), которые не подойдут
к реальной базе и админ-панели.
