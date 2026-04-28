package com.ft.gateway.config;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Gateway CircuitBreaker 설정 동작 검증 테스트.
 *
 * Resilience4JConfig 에 선언된 설정값과 동일한 CircuitBreakerConfig를 직접 구성해
 * 상태 전이(CLOSED→OPEN→HALF_OPEN→CLOSED) 및 서비스별 임계값 차이를 검증한다.
 *
 * Spring 컨텍스트 없이 Resilience4J API만 사용하므로 빠르게 실행된다.
 */
@DisplayName("Gateway CircuitBreaker 설정 동작 검증")
class Resilience4JConfigTest {

    // ── 헬퍼 ─────────────────────────────────────────────────────────────

    private CircuitBreaker buildCB(String name,
                                   int slidingWindowSize,
                                   float failureRateThreshold,
                                   Duration waitDuration,
                                   Duration slowCallThreshold,
                                   float slowCallRateThreshold,
                                   int permittedCallsInHalfOpen) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(slidingWindowSize)
                .minimumNumberOfCalls(slidingWindowSize)
                .failureRateThreshold(failureRateThreshold)
                .waitDurationInOpenState(waitDuration)
                .slowCallDurationThreshold(slowCallThreshold)
                .slowCallRateThreshold(slowCallRateThreshold)
                .permittedNumberOfCallsInHalfOpenState(permittedCallsInHalfOpen)
                .build();
        return CircuitBreakerRegistry.of(config).circuitBreaker(name);
    }

    private CircuitBreaker authCB() {
        return buildCB("authCB", 10, 50f,
                Duration.ofSeconds(10), Duration.ofSeconds(2), 60f, 3);
    }

    private CircuitBreaker notificationCB() {
        return buildCB("notificationCB", 10, 60f,
                Duration.ofSeconds(20), Duration.ofSeconds(4), 70f, 2);
    }

    private CircuitBreaker transactionCB() {
        return buildCB("transactionCB", 10, 50f,
                Duration.ofSeconds(15), Duration.ofSeconds(4), 60f, 3);
    }

    private void recordFailure(CircuitBreaker cb) {
        if (cb.tryAcquirePermission()) {
            cb.onError(0, TimeUnit.MILLISECONDS, new RuntimeException("error"));
        }
    }

    private void recordSuccess(CircuitBreaker cb) {
        if (cb.tryAcquirePermission()) {
            cb.onSuccess(0, TimeUnit.MILLISECONDS);
        }
    }

    private void recordSlowCall(CircuitBreaker cb, long durationMs) {
        if (cb.tryAcquirePermission()) {
            cb.onSuccess(durationMs, TimeUnit.MILLISECONDS);
        }
    }

    // ── authCB ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("authCB — failureRate=50%, slidingWindow=10, slowCall=2s")
    class AuthCB {

        @Test
        @DisplayName("초기 상태는 CLOSED")
        void initialState_isClosed() {
            // given
            CircuitBreaker cb = authCB();

            // when - 아무 호출 없음

            // then
            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        }

        @Test
        @DisplayName("10번 모두 실패 시 OPEN 전환")
        void allFailures_opensCircuit() {
            // given
            CircuitBreaker cb = authCB();

            // when
            for (int i = 0; i < 10; i++) recordFailure(cb);

            // then
            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        }

        @Test
        @DisplayName("OPEN 상태에서 호출 시 CallNotPermittedException 발생")
        void openState_throwsCallNotPermittedException() {
            // given
            CircuitBreaker cb = authCB();
            cb.transitionToOpenState();

            // when & then
            assertThatThrownBy(() -> cb.executeSupplier(() -> "ok"))
                    .isInstanceOf(CallNotPermittedException.class);
        }

        @Test
        @DisplayName("HALF_OPEN에서 3회 성공 시 CLOSED 복귀")
        void halfOpen_successTransitionsToClosed() {
            // given
            CircuitBreaker cb = authCB();
            cb.transitionToOpenState();
            cb.transitionToHalfOpenState();

            // when — permittedNumberOfCallsInHalfOpenState=3 전부 성공
            for (int i = 0; i < 3; i++) recordSuccess(cb);

            // then
            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        }

        @Test
        @DisplayName("HALF_OPEN에서 실패 시 OPEN 재진입")
        void halfOpen_failureTransitionsBackToOpen() {
            // given
            CircuitBreaker cb = authCB();
            cb.transitionToOpenState();
            cb.transitionToHalfOpenState();

            // when
            for (int i = 0; i < 3; i++) recordFailure(cb);

            // then
            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        }

        @Test
        @DisplayName("2s 초과 SlowCall 10회 시 OPEN 전환 — slowCallRate 60% 초과")
        void slowCalls_exceedingThreshold_opensCircuit() {
            // given
            CircuitBreaker cb = authCB();

            // when — 2001ms > 2s threshold → SlowCall 10회 = 100% > 60%
            for (int i = 0; i < 10; i++) recordSlowCall(cb, 2001);

            // then
            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        }
    }

    // ── notificationCB ────────────────────────────────────────────────────

    @Nested
    @DisplayName("notificationCB — failureRate=60% (비핵심 서비스, 완화된 임계값)")
    class NotificationCB {

        @Test
        @DisplayName("5번 실패(50%)로는 CLOSED 유지 — 60% 미만이므로 열리지 않음")
        void fiveFailures_staysClosedBecauseThresholdIs60Percent() {
            // given
            CircuitBreaker cb = notificationCB();

            // when — 5번 실패 + 5번 성공 = 50% 실패율
            for (int i = 0; i < 5; i++) recordFailure(cb);
            for (int i = 0; i < 5; i++) recordSuccess(cb);

            // then — 50% < 60% 이므로 CLOSED 유지
            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        }

        @Test
        @DisplayName("6번 실패(60%)로 OPEN 전환 — 임계값 도달")
        void sixFailures_opensCircuit() {
            // given
            CircuitBreaker cb = notificationCB();

            // when — 6번 실패 + 4번 성공 = 60% 실패율
            for (int i = 0; i < 6; i++) recordFailure(cb);
            for (int i = 0; i < 4; i++) recordSuccess(cb);

            // then — 60% >= 60% 이므로 OPEN
            assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        }

        @Test
        @DisplayName("50% 실패 시 authCB는 OPEN, notificationCB는 CLOSED — 서비스별 임계값 차이")
        void sameFailureRate_authOpens_notificationStaysClosed() {
            // given
            CircuitBreaker notification = notificationCB();
            CircuitBreaker auth = authCB();

            // when — 양쪽 모두 5번 실패 + 5번 성공 = 50% 실패율
            for (int i = 0; i < 5; i++) {
                recordFailure(notification);
                recordFailure(auth);
            }
            for (int i = 0; i < 5; i++) {
                recordSuccess(notification);
                recordSuccess(auth);
            }

            // then
            assertThat(auth.getState()).isEqualTo(CircuitBreaker.State.OPEN);        // 50% >= 50%
            assertThat(notification.getState()).isEqualTo(CircuitBreaker.State.CLOSED); // 50% < 60%
        }
    }

    // ── transactionCB vs authCB SlowCall 비교 ─────────────────────────────

    @Nested
    @DisplayName("transactionCB — slowCall=4s (Kafka 발행 지연 허용)")
    class TransactionCB {

        @Test
        @DisplayName("3s 호출은 transactionCB SlowCall 아님 — authCB(2s 기준)와 차이")
        void threeSecondCall_isNotSlowForTransaction_butIsForAuth() {
            // given
            CircuitBreaker transaction = transactionCB();
            CircuitBreaker auth = authCB();

            // when — 3001ms 호출 10회
            for (int i = 0; i < 10; i++) {
                recordSlowCall(transaction, 3001);
                recordSlowCall(auth, 3001);
            }

            // then
            assertThat(auth.getState()).isEqualTo(CircuitBreaker.State.OPEN);        // 3s > 2s → SlowCall → OPEN
            assertThat(transaction.getState()).isEqualTo(CircuitBreaker.State.CLOSED); // 3s < 4s → 정상 호출
        }
    }
}
