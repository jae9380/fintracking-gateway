package com.ft.gateway.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Gateway Circuit Breaker 설정 — Resilience4J (Reactive)
 * Customizer<ReactiveResilience4JCircuitBreakerFactory> 를 통해 서비스별로 개별 설정한다.
 * ┌─────────────────────────────────────────────────────────────────────────────────────────┐
 * │                         Circuit Breaker 설정 항목 요약                                     │
 * ├──────────────────┬──────────────┬───────────┬──────────────┬───────────┬────────────────┤
 * │    인스턴스        │slidingWindow │failureRate│waitDuration  │ SlowCall  │ timeout        │
 * ├──────────────────┼──────────────┼───────────┼──────────────┼───────────┼────────────────┤
 * │ authCB           │     10       │   50 %    │    10 s      │   2 s     │   3 s          │
 * │ accountCB        │     10       │   50 %    │    15 s      │   3 s     │   4 s          │
 * │ transactionCB    │     10       │   50 %    │    15 s      │   4 s     │   5 s          │
 * │ budgetCB         │     10       │   50 %    │    15 s      │   3 s     │   4 s          │
 * │ notificationCB   │     10       │   60 %    │    20 s      │   4 s     │   4 s          │
 * └──────────────────┴──────────────┴───────────┴──────────────┴───────────┴────────────────┘
 *
 * 설정 항목 설명:
 *   slidingWindowSize              : 실패율/SlowCall 계산 기준이 되는 최근 호출 수
 *   failureRateThreshold           : 이 비율 이상 실패하면 OPEN 전환 (%)
 *   waitDurationInOpenState        : OPEN 유지 시간, 경과 후 HALF_OPEN으로 전환
 *   slowCallDurationThreshold      : 이 시간 초과 호출을 SlowCall로 분류
 *   slowCallRateThreshold          : SlowCall 비율이 이 값 이상이면 OPEN 전환 (%)
 *   permittedNumberOfCallsInHalfOpen: HALF_OPEN 상태에서 허용할 테스트 호출 수
 *   timeoutDuration (TimeLimiter)  : 다운스트림 응답 대기 최대 시간, 초과 시 TimeoutException
 *
 * 서비스별 설정 근거:
 *   authCB        — 장애 시 전체 서비스 이용 불가. 빠른 감지(SlowCall 2s)와 빠른 복구 확인(10s) 우선.
 *   accountCB     — AES-256 암호화 오버헤드를 고려해 SlowCall 기준을 3s로 설정.
 *   transactionCB — Kafka 발행 포함으로 지연 가능성이 있어 SlowCall/timeout을 가장 넉넉히 설정.
 *   budgetCB      — Chain of Responsibility 연산 포함, account와 동일한 중간 수준 설정.
 *   notificationCB— 비핵심 서비스이므로 failureRate(60%), slowCallRate(70%) 기준을 완화.
 */
@Configuration
public class Resilience4JConfig {
    @Bean
    public Customizer<ReactiveResilience4JCircuitBreakerFactory> serviceCircuitBreakerCustomizer() {
        return factory -> {
            // auth-service
            factory.configure(builder -> builder
                    .circuitBreakerConfig(CircuitBreakerConfig.custom()
                            .slidingWindowSize(10)
                            .minimumNumberOfCalls(10)
                            .failureRateThreshold(50)
                            .waitDurationInOpenState(Duration.ofSeconds(10))
                            .slowCallDurationThreshold(Duration.ofSeconds(2))
                            .slowCallRateThreshold(60)
                            .permittedNumberOfCallsInHalfOpenState(3)
                            .build())
                    .timeLimiterConfig(TimeLimiterConfig.custom()
                            .timeoutDuration(Duration.ofSeconds(3))
                            .build())
                    .build(), "authCB");

            // account-service
            factory.configure(builder -> builder
                    .circuitBreakerConfig(CircuitBreakerConfig.custom()
                            .slidingWindowSize(10)
                            .minimumNumberOfCalls(10)
                            .failureRateThreshold(50)
                            .waitDurationInOpenState(Duration.ofSeconds(15))
                            .slowCallDurationThreshold(Duration.ofSeconds(3))
                            .slowCallRateThreshold(60)
                            .permittedNumberOfCallsInHalfOpenState(3)
                            .build())
                    .timeLimiterConfig(TimeLimiterConfig.custom()
                            .timeoutDuration(Duration.ofSeconds(4))
                            .build())
                    .build(), "accountCB");

            // transaction-service
            factory.configure(builder -> builder
                    .circuitBreakerConfig(CircuitBreakerConfig.custom()
                            .slidingWindowSize(10)
                            .minimumNumberOfCalls(10)
                            .failureRateThreshold(50)
                            .waitDurationInOpenState(Duration.ofSeconds(15))
                            .slowCallDurationThreshold(Duration.ofSeconds(4))
                            .slowCallRateThreshold(60)
                            .permittedNumberOfCallsInHalfOpenState(3)
                            .build())
                    .timeLimiterConfig(TimeLimiterConfig.custom()
                            .timeoutDuration(Duration.ofSeconds(5))
                            .build())
                    .build(), "transactionCB");

            // budget-service
            factory.configure(builder -> builder
                    .circuitBreakerConfig(CircuitBreakerConfig.custom()
                            .slidingWindowSize(10)
                            .minimumNumberOfCalls(10)
                            .failureRateThreshold(50)
                            .waitDurationInOpenState(Duration.ofSeconds(15))
                            .slowCallDurationThreshold(Duration.ofSeconds(3))
                            .slowCallRateThreshold(60)
                            .permittedNumberOfCallsInHalfOpenState(3)
                            .build())
                    .timeLimiterConfig(TimeLimiterConfig.custom()
                            .timeoutDuration(Duration.ofSeconds(4))
                            .build())
                    .build(), "budgetCB");

            // notification-service
            factory.configure(builder -> builder
                    .circuitBreakerConfig(CircuitBreakerConfig.custom()
                            .slidingWindowSize(10)
                            .minimumNumberOfCalls(10)
                            .failureRateThreshold(60)
                            .waitDurationInOpenState(Duration.ofSeconds(20))
                            .slowCallDurationThreshold(Duration.ofSeconds(4))
                            .slowCallRateThreshold(70)
                            .permittedNumberOfCallsInHalfOpenState(2)
                            .build())
                    .timeLimiterConfig(TimeLimiterConfig.custom()
                            .timeoutDuration(Duration.ofSeconds(4))
                            .build())
                    .build(), "notificationCB");
        };
    }
}
