# URL Shortener

A URL shortener built with **Spring Boot 3** and **Redis**, a classic system-design project. It supports short-code generation, custom aliases, link expiry, click statistics and per-IP rate limiting, and runs with a single `docker compose` command.

## Features

- Shorten any `http`/`https` URL into a 6+ character Base62 code
- Custom aliases (3-32 characters: letters, digits, `_`, `-`)
- Configurable expiry per link (default 30 days), enforced by Redis TTL
- `302` redirect with click counting
- Statistics endpoint (clicks, creation time, time left)
- Per-IP rate limiting backed by Redis (atomic Lua script)
- Input validation with clean JSON error responses
- Multi-stage Dockerfile and a Compose file that runs the app and Redis together

## Tech stack

| Layer | Choice |
|---|---|
| Language / framework | Java 17, Spring Boot 3.3 |
| Datastore | Redis 7 |
| Redis client | Spring Data Redis (Lettuce) |
| Build | Maven |
| Containers | Docker, Docker Compose |

## Architecture

```
                    +--------------------+
  Client  ------->  |  RateLimitFilter   |  INCR + EXPIRE (Lua) per IP
                    +---------+----------+
                              |
                    +---------v----------+
                    |   UrlController    |  POST /api/v1/shorten
                    |                    |  GET  /{code}  -> 302
                    |                    |  GET  /api/v1/stats/{code}
                    +---------+----------+
                              |
                    +---------v----------+
                    |     UrlService     |  Base62, validation, TTL
                    +---------+----------+
                              |
                    +---------v----------+
                    |       Redis        |
                    +--------------------+
```

### Redis data model

| Key | Type | Purpose |
|---|---|---|
| `counter:url-id` | String | Global counter. `INCR` produces a unique ID for each new link |
| `url:{code}` | Hash | Fields: `longUrl`, `createdAt`, `clicks`. Has a TTL |
| `rl:{bucket}:{ip}` | String | Rate-limit counter, expires after 60 seconds |

### How a short code is made

1. `INCR counter:url-id` returns a unique number.
2. An offset of 1,000,000,000 is added, so codes start at 6 characters.
3. The number is Base62-encoded (`0-9A-Za-z`), for example `1000000001` becomes `15ftgH`.
4. `HSETNX url:{code} longUrl ...` claims the code atomically. If it already exists (for example a custom alias took it), the service tries the next ID.

## Getting started

### Prerequisites

- Docker Desktop (Windows/macOS) or Docker Engine (Linux)
- For local development without the app container: JDK 17+ and Maven 3.8+

### Option A: Run everything with Docker (recommended)

```bash
git clone https://github.com/Tanvipowar11/url-shortener.git
cd url-shortener
docker compose up --build -d
```

The first build takes a few minutes. The app is then available at `http://localhost:8080`.

```bash
docker compose logs -f app    # view logs (Ctrl+C stops viewing, not the app)
docker compose down           # stop everything; saved links stay in the volume
```

### Option B: Run the app locally, Redis in Docker

```bash
docker compose up -d redis
mvn test
mvn spring-boot:run
```

Stop any other process using port 8080 first.

### Configuration

Set these as environment variables, or edit `src/main/resources/application.yml`.

| Variable / property | Default | Description |
|---|---|---|
| `REDIS_HOST` | `localhost` | Redis host (`redis` inside Compose) |
| `REDIS_PORT` | `6379` | Redis port |
| `SHORTENER_BASE_URL` | `http://localhost:8080` | Prefix used in returned short URLs |
| `shortener.default-ttl` | `30d` | Expiry when `ttlSeconds` is not given |
| `shortener.rate-limit.enabled` | `true` | Turn rate limiting on or off |
| `shortener.rate-limit.shorten-per-minute` | `10` | `POST /api/v1/shorten` limit per IP |
| `shortener.rate-limit.redirect-per-minute` | `120` | All `GET` requests limit per IP |

## API

### Create a short URL

`POST /api/v1/shorten`

| Field | Type | Required | Notes |
|---|---|---|---|
| `url` | string | yes | Must be a valid `http` or `https` URL, up to 2048 characters |
| `customAlias` | string | no | 3-32 characters: letters, digits, `_`, `-` |
| `ttlSeconds` | number | no | Minimum 60 |

```bash
curl -X POST http://localhost:8080/api/v1/shorten \
  -H "Content-Type: application/json" \
  -d '{"url":"https://github.com","customAlias":"gh","ttlSeconds":3600}'
```

Response `201 Created`:

```json
{
  "code": "gh",
  "shortUrl": "http://localhost:8080/gh",
  "longUrl": "https://github.com",
  "expiresAt": "2026-11-21T13:55:03.166Z"
}
```

### Follow a short URL

`GET /{code}` returns `302 Found` with a `Location` header, and increments the click counter.

```bash
curl -i http://localhost:8080/gh
```

### Get statistics

`GET /api/v1/stats/{code}`

```bash
curl http://localhost:8080/api/v1/stats/gh
```

```json
{
  "code": "gh",
  "longUrl": "https://github.com",
  "createdAt": "2026-11-21T12:55:03.166Z",
  "clicks": 3,
  "expiresInSeconds": 3412
}
```

### Status codes

| Code | Meaning |
|---|---|
| `201` | Short URL created |
| `302` | Redirect to the original URL |
| `400` | Invalid URL, alias or TTL |
| `404` | Code not found or expired |
| `409` | Custom alias already in use |
| `429` | Rate limit exceeded. See the `Retry-After` header |

### Windows PowerShell examples

PowerShell's `curl` is an alias for another command, so use `Invoke-RestMethod`:

```powershell
$r = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/shorten `
  -ContentType "application/json" `
  -Body '{"url":"https://github.com"}'
$r | Format-List
Start-Process $r.shortUrl
Invoke-RestMethod http://localhost:8080/api/v1/stats/$($r.code) | Format-List
```

## Rate limiting

- Fixed 60-second window per client IP, stored in Redis.
- `POST /api/v1/shorten` is limited more strictly than reads.
- The `INCR` and `EXPIRE` run in one Lua script, so the counter can never be left without an expiry.
- Responses include `X-RateLimit-Limit` and `X-RateLimit-Remaining`. Blocked requests get `429` and `Retry-After`.
- If Redis is unreachable, the limiter fails open and logs a warning.
- Clients are identified by `getRemoteAddr()`. Behind a reverse proxy, change the filter to read `X-Forwarded-For`.

Quick test (PowerShell):

```powershell
foreach ($i in 1..12) {
  try {
    Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/shorten -ContentType "application/json" -Body '{"url":"https://example.com"}' | Out-Null
    "$i : OK"
  } catch { "$i : BLOCKED ($($_.Exception.Response.StatusCode.value__))" }
}
```

## Project structure

```
url-shortener/
├── Dockerfile                  # multi-stage build (Maven, then JRE)
├── docker-compose.yml          # redis + app
├── pom.xml
└── src/
    ├── main/java/com/example/urlshortener/
    │   ├── UrlShortenerApplication.java
    │   ├── config/
    │   │   ├── ShortenerProperties.java   # typed configuration
    │   │   └── RateLimitFilter.java       # per-IP limiter
    │   ├── controller/UrlController.java
    │   ├── service/UrlService.java
    │   ├── dto/                           # request/response records
    │   ├── exception/                     # ApiException + global handler
    │   └── util/Base62.java
    ├── main/resources/application.yml
    └── test/java/.../util/Base62Test.java
```

## Design notes

### Requirements (assumed)

- Create short links and redirect with very low latency.
- Reads far outnumber writes.
- Links can expire. Basic click analytics.

### Back-of-envelope capacity

Example assumptions: 100 million new links per month, 100 reads per write.

| Metric | Estimate |
|---|---|
| Writes | 100M / (30 x 86,400 s), about 40 per second |
| Reads | about 4,000 per second on average (peaks maybe 3 to 5x) |
| Storage per link | about 500 bytes, so about 50 GB per month |
| Code space | 62^6 is about 56.8 billion codes, enough for roughly 40 years at this rate |

Because links expire after 30 days by default, Redis memory stays bounded.

### Key decisions

| Decision | Reason | Trade-off |
|---|---|---|
| Counter + Base62 for codes | No collisions, no retries, short codes | Codes are sequential and guessable. Don't use for secrets. Hash or shuffle the ID if needed |
| Redis as the only store | One hop per lookup, built-in TTL | Memory cost, durability depends on persistence settings (AOF is on in Compose) |
| `302`, not `301` | Browsers don't cache it, so every click reaches the server and is counted | More redirect traffic than a cached `301` |
| `HSETNX` to claim a code | Atomic. Two requests can't take the same alias | The follow-up field writes are separate commands, so a crash in between can leave a partial entry |
| Fixed-window rate limit | Simple and cheap in Redis | Allows bursts at window boundaries. A sliding window or token bucket is smoother |
| Fail-open limiter | An outage in Redis doesn't also block the API | Limits aren't enforced while Redis is down |

### Scaling path

1. **Stateless app instances** behind a load balancer. All state is in Redis.
2. **Redis replicas** for reads, with Sentinel or a managed service for failover.
3. **Counter at scale:** give each app instance a block of IDs (for example 1,000 at a time) to cut Redis round trips, or move to a sharded counter.
4. **Durability:** keep Postgres as the source of truth and Redis as a cache (cache-aside).
5. **Redis Cluster**, sharding by code.
6. **CDN or edge caching** for hot links (this requires a cacheable redirect, so click counting would move to log processing).
7. **Analytics off the hot path:** publish click events to a queue and aggregate them asynchronously.

## Known limitations

- Duplicate long URLs get different codes. There is no deduplication.
- No user accounts, so links can't be edited or deleted through the API.
- The create step is not fully atomic (see the `HSETNX` note above). A Lua script would fix this.
- No malicious-URL screening.
- Integration tests are not included yet. Only `Base62` has unit tests.

## Roadmap

- [ ] Testcontainers integration tests
- [ ] Deduplicate identical long URLs
- [ ] Sliding-window or token-bucket rate limiting
- [ ] Postgres + Redis cache-aside
- [ ] Delete/update endpoints with API keys
- [ ] Asynchronous click analytics

## Troubleshooting

| Problem | Fix |
|---|---|
| `failed to connect to the docker API` | Start Docker Desktop and wait for "Engine running" |
| Port 8080 already allocated | Stop `mvn spring-boot:run` or any other app on 8080, then retry |
| `409 Conflict` on create | The custom alias is taken. Choose another |
| `429 Too Many Requests` | Wait for the number of seconds in `Retry-After`, or raise the limit in `application.yml` |
| Changes not showing in Docker | Rebuild with `docker compose up --build -d` |

## License

MIT, or choose your own license.
