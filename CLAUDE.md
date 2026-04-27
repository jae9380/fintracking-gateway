# fintracking-gateway

Spring Cloud Gateway — JWT 검증, 라우팅, 로드밸런싱

---

## 라우팅 구조

```yaml
# 패턴: /서비스명-service/** → lb://서비스명 (StripPrefix=1)
/auth-service/**         → lb://FINTRACKING-AUTH         (공개)
/account-service/**      → lb://FINTRACKING-ACCOUNT      (JWT 필요)
/transaction-service/**  → lb://FINTRACKING-TRANSACTION  (JWT 필요)
/budget-service/**       → lb://FINTRACKING-BUDGET       (JWT 필요)
/notification-service/** → lb://FINTRACKING-NOTIFICATION (JWT 필요)
```

- **StripPrefix=1**: `/account-service/api/v1/accounts` → `/api/v1/accounts` 변환
- **lb://**: Eureka 기반 로드밸런싱

---

## AuthorizationHeaderFilter

JWT 검증 후 `X-User-Id` 헤더를 다운스트림 서비스에 주입.

```
요청 → Gateway
  → Bearer {accessToken} 검증
  → X-User-Id: {userId} 헤더 추가
  → 다운스트림 서비스로 전달
```

다운스트림 컨트롤러에서:
```java
@RequestHeader("X-User-Id") Long userId
```

- auth-service 경로는 **AuthorizationHeaderFilter 미적용** (로그인/회원가입은 토큰 불필요)

---

## JWT Secret

- `application-secret.yml` (공통) `jwt.secret` 사용
- auth-service와 **동일한 시크릿 공유** (서명 검증 필요)

---

## SecurityConfig

```java
@Configuration @EnableWebFluxSecurity
public class SecurityConfig {
    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http.csrf(csrf -> csrf.disable())
                .authorizeExchange(ex -> ex.anyExchange().permitAll())
                .build();
    }
}
```

- Spring Security가 classpath에 있으면 반드시 이 설정 필요 (없으면 기본적으로 모든 요청 차단)
- JWT 검증은 `AuthorizationHeaderFilter`에서 수행, Security 레벨에서는 `permitAll`

---

## Swagger UI 통합

`/swagger-ui.html` → 각 서비스 API docs 통합 뷰

```yaml
springdoc.swagger-ui.urls:
  - name: auth-service
    url: /v3/api-docs/auth-service   # → lb://FINTRACKING-AUTH/v3/api-docs 로 라우팅
```

---

## 설정 파일

- `application.yml` (로컬): 라우트 정의, 포트(8000)
- `application-secret.yml` (Config 서버 공통): `jwt.secret`
