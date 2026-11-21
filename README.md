# URL Shortener (Spring Boot + Redis)

## Run everything with Docker (one command)
    docker compose up --build -d
App: http://localhost:8080  |  Stop: `docker compose down`

## Run locally (Redis in Docker, app via Maven)
    docker compose up -d redis
    mvn spring-boot:run

## API
- POST /api/v1/shorten   {"url": "...", "customAlias": "opt", "ttlSeconds": 3600}
- GET  /{code}           302 redirect
- GET  /api/v1/stats/{code}

## Rate limiting
Per client IP, fixed 60-second window, enforced in Redis (atomic Lua script).
Defaults: 10 shorten requests/min, 120 reads/min. Over the limit -> HTTP 429 + Retry-After.
Configure in application.yml under `shortener.rate-limit`.
