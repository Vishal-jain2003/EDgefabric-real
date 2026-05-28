# {{JIRA_KEY}}: {{STORY_SUMMARY}}

**ADR:** {{CONFLUENCE_URL}}
**Confluence Page ID:** {{CONFLUENCE_PAGE_ID}}
**Module:** {{MODULE}}
**Strategy:** Strategy N — {{STRATEGY_NAME}}

## API Contracts

### Controller
| Endpoint | Method | Request Body | Response | Notes |
|----------|--------|-------------|----------|-------|
| `/api/v1/...` | POST | `CreateRequest` | `200 ResponseDTO` | @Valid |

### Service Layer
| Method | Signature | Business Rules |
|--------|-----------|----------------|
| `create` | `ResponseDTO create(CreateRequest req)` | rule1, rule2 |

### Model / Entity
| Class | Key Fields | Notes |
|-------|-----------|-------|
| `FooModel` | `id, name, status` | @Data @Builder, in model/ |

### Exception Mapping
| Exception class | HTTP | Trigger condition |
|----------------|------|------------------|
| `FooNotFoundException` | 404 | resource not found |

## Implementation Tasks

- [ ] Create model class in `{{MODULE}}/src/main/java/com/edgefabric/{{MODULE}}/model/`
- [ ] Add exceptions to `{{MODULE}}/.../exception/`
- [ ] Implement service in `{{MODULE}}/.../service/`
- [ ] Create controller in `{{MODULE}}/.../controller/`
- [ ] Write unit tests (`@ExtendWith(MockitoExtension.class)`) for service
- [ ] Write integration tests (`@WebMvcTest`) for controller
- [ ] Write E2E test in `testing_edgefabric` (if user-facing endpoint)
- [ ] Update `application.yml` / `docker-compose.yml` if config changes needed

## Config Changes

{{Any application.yml, docker-compose.yml, or Jenkinsfile changes required. "None" if not applicable.}}
