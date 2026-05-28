# EdgeFabric — Java/Spring Conventions (CACHED snippet)

## Layering (strict)
`Controller → Service → Model`. Never skip layers; controllers never touch repositories/stores.

## Package layout
`com.edgefabric.<module>.{controller,service,model,exception,config,wal,...}`

## Annotations
- DI: `@RequiredArgsConstructor` (Lombok). **No** `@Autowired` field injection.
- Validation: `@Valid` on request bodies; `@NotNull` / `@NotBlank` on model fields.
- Config: `@ConfigurationProperties(prefix = "...")` — no hardcoded constants.
- REST: `@RestController @RequestMapping("/api/v1/...")` — return `ResponseEntity<T>` with explicit status codes.

## Exceptions
- Custom per module under `exception/`.
- Map via `GlobalExceptionHandler` with `@ExceptionHandler` + `@ResponseStatus`.
- Never throw raw `RuntimeException`.
- Use `Optional.orElseThrow(...)`, not null checks.

## Async
WebFlux `WebClient` (`Mono`/`Flux`) for inter-service calls. **Never** `.block()`.

## Tests
JUnit Jupiter 5 + Mockito. Coverage ≥ 80%. Tests live in `<module>/src/test/java/...`.

## Build verify
```
mvn compile -pl <module> -Dmaven.repo.local=/tmp/.m2
# if consistent-hashing changed first:
mvn install -pl consistent-hashing -DskipTests -Dmaven.repo.local=/tmp/.m2
```

## Patterns to NEVER use
- Hardcoded IPs / credentials → use `@ConfigurationProperties`
- Field injection → use constructor injection (Lombok)
- `.block()` in reactive chain
- Raw `RuntimeException`
- Controller calling repository directly
