# fintracking-gateway

> API 게이트웨이 — JWT 검증 · 라우팅 · 로드밸런싱 · Circuit Breaker

---

## 사용 기술

| 분류        | 기술                                |
| ----------- | ----------------------------------- |
| Gateway     | Spring Cloud Gateway (WebFlux 기반) |
| 인증        | JWT (jjwt)                          |
| 서비스 발견 | Spring Cloud Eureka Client          |
| 회복 탄력성 | Resilience4J Circuit Breaker        |
| 보안        | Spring Security                     |
| 설정 관리   | Spring Cloud Config                 |

> **WebFlux 기반**: Spring MVC가 아닌 리액티브(Reactive) 스택입니다.
> 비동기 논블로킹으로 동작하여 다수의 서비스 요청을 효율적으로 처리합니다.

---

## 전체 요청 흐름

```
[클라이언트]
    │
    │  HTTP 요청 (포트 8000)
    ▼
[fintracking-gateway]
    │
    ├── GlobalFilter  ← 모든 요청 로깅 (요청 ID, 경로, 메서드)
    │
    ├── LoggingFilter ← 요청/응답 상세 로그
    │
    ├── 라우팅 테이블 매칭 ─────────────────────────────────────────────────┐
    │                                                                        │
    │  /auth-service/**    → CustomFilter만 적용 (JWT 검증 없음)             │
    │  /account-service/** → AuthorizationHeaderFilter 적용 (JWT 필수)       │
    │  기타 서비스         → AuthorizationHeaderFilter 적용 (JWT 필수)       │
    │                                                                        │
    ├── [AuthorizationHeaderFilter]                                          │
    │     │ 1. Authorization: Bearer {token} 헤더 추출                       │
    │     │ 2. JWT 서명 검증 (jwt.secret 사용)                               │
    │     │ 3. userId 추출                                                   │
    │     │ 4. X-User-Id: {userId} 헤더 추가 후 다운스트림으로 전달         │
    │     │                                                                  │
    │     └── 검증 실패 → 401 Unauthorized 즉시 반환                         │
    │                                                                        │
    │  Eureka에서 인스턴스 조회 → 로드밸런싱                                 │
    ▼                                                                        │
[마이크로서비스]  ←───────────────────────────────────────────────────────┘
    │
    │  @RequestHeader("X-User-Id") Long userId  ← 컨트롤러에서 바로 사용
    ▼
[응답 반환]
```

---

## 라우팅 구조

```yaml
# StripPrefix=1: 경로 앞 세그먼트 제거 후 전달
# /account-service/api/v1/accounts → /api/v1/accounts

라우트 테이블:
┌─────────────────────────┬─────────────────────────────────────┬───────────────┐
│ 클라이언트 경로             │ 전달되는 서비스                         │ JWT 검증       │
├─────────────────────────┼─────────────────────────────────────┼───────────────┤
│ /auth-service/**        │ lb://FINTRACKING-AUTH               │ ✗ (공개)       │
│ /account-service/**     │ lb://FINTRACKING-ACCOUNT            │ ✓ (필수)       │
│ /transaction-service/** │ lb://FINTRACKING-TRANSACTION        │ ✓ (필수)       │
│ /budget-service/**      │ lb://FINTRACKING-BUDGET             │ ✓ (필수)       │
│ /notification-service/**│ lb://FINTRACKING-NOTIFICATION       │ ✓ (필수)       │
└─────────────────────────┴─────────────────────────────────────┴───────────────┘

lb:// = Eureka 기반 로드밸런싱
```

**왜 /auth-service는 JWT 검증이 없는가?**
로그인과 회원가입 요청에는 아직 토큰이 없기 때문입니다.
토큰을 발급받기 위해 auth-service를 호출하는 것이므로 공개 경로여야 합니다.

---

## AuthorizationHeaderFilter

JWT를 검증하고 userId를 다운스트림 서비스에 전달하는 핵심 필터입니다.

```java
// 검증 성공 시 — X-User-Id 헤더 추가
ServerHttpRequest mutatedRequest = request.mutate()
    .header("X-User-Id", String.valueOf(userId))
    .build();

// 다운스트림 컨트롤러에서 바로 사용
@GetMapping("/api/v1/accounts")
public ApiResponse<List<AccountResponse>> getAccounts(
    @RequestHeader("X-User-Id") Long userId  // Gateway가 주입해준 userId
) { ... }
```

**@RefreshScope 적용:**
Config 서버에서 `jwt.secret`이 변경되면 서비스 재시작 없이 `/actuator/refresh`로 즉시 적용됩니다.

---

## Circuit Breaker — Resilience4J

다운스트림 서비스에 장애가 생겼을 때 Gateway 전체가 멈추지 않도록 격리합니다.

```
정상 상태 (CLOSED):
  클라이언트 → Gateway → account-service (정상 응답)

account-service 장애 발생:
  클라이언트 → Gateway → account-service (타임아웃/에러 반복)
                         Circuit OPEN → /fallback 으로 리다이렉트

30초 후 (HALF_OPEN):
  소수 요청 테스트 → 성공 시 CLOSED 복구
```

```java
// FallbackController.java — Circuit OPEN 시 응답
@GetMapping("/fallback")
public ApiResponse<String> fallback() {
    return ApiResponse.error("서비스가 일시적으로 사용 불가합니다. 잠시 후 다시 시도해주세요.");
}
```

---

## 필터 구성

| 필터                        | 적용 범위        | 역할                      |
| --------------------------- | ---------------- | ------------------------- |
| `GlobalFilter`              | 모든 요청        | 공통 로깅, 요청 ID 부여   |
| `LoggingFilter`             | 모든 요청        | 요청/응답 상세 로그       |
| `CustomFilter`              | 라우트별         | 라우트 수준 전/후처리     |
| `AuthorizationHeaderFilter` | 인증 필요 라우트 | JWT 검증 + X-User-Id 주입 |

---

## Spring Security 설정

Spring Security가 classpath에 있으면 기본적으로 모든 요청을 차단합니다.
JWT 검증은 `AuthorizationHeaderFilter`에서 수행하므로, Security 레벨에서는 전체 허용합니다.

```java
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {
    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
            .csrf(csrf -> csrf.disable())
            .authorizeExchange(ex -> ex.anyExchange().permitAll()) // JWT 검증은 필터에서
            .build();
    }
}
```

---

## JWT Secret 공유

Gateway와 auth-service는 **동일한 JWT Secret을 공유**합니다.
auth-service가 이 Secret으로 토큰을 서명하고, Gateway가 이 Secret으로 서명을 검증합니다.

```
Config 서버 (application-secret.yml)
├── jwt.secret: "{cipher}암호화된값"
│
├── auth-service가 로드 → 토큰 서명 (발급)
└── gateway가 로드    → 토큰 검증 (인증)
```

---

## 패키지 구조

```
com.ft.gateway
├── filter/
│   ├── GlobalFilter.java              ← 전역 필터 (모든 요청)
│   ├── LoggingFilter.java             ← 요청/응답 로깅
│   ├── CustomFilter.java              ← 라우트별 커스텀 필터
│   └── AuthorizationHeaderFilter.java ← JWT 검증 + X-User-Id 주입
│
├── config/
│   ├── SecurityConfig.java            ← WebFlux Security 설정
│   └── Resilience4JConfig.java        ← Circuit Breaker 설정
│
└── controller/
    └── FallbackController.java        ← Circuit Breaker Fallback 엔드포인트
```

---

## 설정 파일

```yaml
# application.yml (로컬)
server:
  port: 8000

spring:
  cloud:
    gateway:
      routes:
        - id: auth-service
          uri: lb://FINTRACKING-AUTH
          predicates:
            - Path=/auth-service/**
          filters:
            - StripPrefix=1
            - CustomFilter

        - id: account-service
          uri: lb://FINTRACKING-ACCOUNT
          predicates:
            - Path=/account-service/**
          filters:
            - StripPrefix=1
            - AuthorizationHeaderFilter
            - CircuitBreaker=account-cb,fallbackUri=/fallback
```

---

## 테스트

```
test/
├── config/
│   └── Resilience4JConfigTest.java  ← Circuit Breaker 설정 검증
└── FintrackingGatewayApplicationTests.java
```
