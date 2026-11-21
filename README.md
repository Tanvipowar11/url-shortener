# URL Shortener (Spring Boot + Redis)

## Run
1. `docker compose up -d`   (starts Redis on :6379)
2. `mvn spring-boot:run`    (starts API on :8080)

## Try it
curl -X POST localhost:8080/api/v1/shorten -H "Content-Type: application/json" -d '{"url":"https://github.com"}'
curl -i localhost:8080/<code>
curl localhost:8080/api/v1/stats/<code>
