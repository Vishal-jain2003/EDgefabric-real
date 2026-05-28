---
name: java-spring-conventions
description: How to write Java 21 / Spring Boot 3 code in EdgeFabric — layering, DI, exceptions, async, validation, tests. Load this whenever writing or reviewing Java code, adding a new endpoint, creating a service, or fixing Sonar findings.
---

# Skill: Java / Spring Conventions

## Strict layering

```
Controller → Service → (Model | external client)
```

- Controllers: HTTP only — parse, validate, delegate, return `ResponseEntity<T>`.
- Services: business logic, transactions, reactive composition.
- Controllers **never** call repositories or stores directly.

## Package layout

```
com.edgefabric.<module>.{controller,service,model,exception,config,gossip,wal,...}
```

## Dependency injection

- **Constructor injection only**, via Lombok `@RequiredArgsConstructor`.
- **Never** field injection (`@Autowired` on a field).
- Configuration via `@ConfigurationProperties(prefix = "...")` — no
  hardcoded constants, no `@Value` sprawl.

```java
@Service
@RequiredArgsConstructor
public class CacheService {
    private final CacheStore store;
    private final GossipProperties gossip;  // bound config
}
```

## REST endpoints

- `@RestController` + `@RequestMapping("/api/v1/...")`.
- `@Valid` on request bodies; `@NotNull` / `@NotBlank` on DTO fields.
- Return `ResponseEntity<T>` with explicit status codes — never default 200 implicitly.

## Exceptions

- Define per-module exceptions under `<module>/exception/`.
- Map all of them in `GlobalExceptionHandler` with
  `@ExceptionHandler` + `@ResponseStatus`.
- **Never** throw raw `RuntimeException` from production code.
- Prefer `Optional.orElseThrow(() -> new XyzNotFoundException(id))`
  over null checks.

## Async / reactive

- Inter-service calls use WebFlux `WebClient` returning `Mono` / `Flux`.
- **Never `.block()`** in a reactive chain — propagate the publisher.
- Use `.timeout(Duration.ofMillis(...))` from `@ConfigurationProperties` —
  no magic numbers.

## Testing

- JUnit Jupiter 5 + Mockito.
- Tests in `<module>/src/test/java/...`, mirror production package.
- Coverage **≥ 80%** (line). Sonar gate fails below.
- Naming: `<ClassUnderTest>Test` for unit, `<Feature>IT` for integration.

## Build / verify locally

```bash
mvn compile -pl <module> -Dmaven.repo.local=/tmp/.m2

# If consistent-hashing changed first:
mvn install -pl consistent-hashing -DskipTests -Dmaven.repo.local=/tmp/.m2
```

## Patterns to NEVER use

| ❌ Bad | ✅ Good |
|-------|---------|
| Hardcoded IPs / credentials | `@ConfigurationProperties` |
| `@Autowired` on field | Constructor injection (Lombok) |
| `.block()` in reactive chain | Compose with `Mono`/`Flux` operators |
| Raw `RuntimeException` | Custom exception + `GlobalExceptionHandler` |
| Controller calling repository | Controller → Service → Repository |
| `@Value("${x}")` scattered | One `@ConfigurationProperties` class |
| Null returns | `Optional<T>` |
