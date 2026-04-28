package com.ft.gateway.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Circuit Breaker OPEN 상태일 때 각 서비스의 fallback 응답을 반환한다.
 * Gateway 라우트의 CircuitBreaker 필터에서 forward:/fallback/{service}로 전달된다.
 */
@Slf4j
@RestController
public class FallbackController {

    @RequestMapping("/fallback/auth")
    public Mono<ResponseEntity<Map<String, Object>>> authFallback(ServerWebExchange exchange) {
        return fallback("auth-service");
    }

    @RequestMapping("/fallback/account")
    public Mono<ResponseEntity<Map<String, Object>>> accountFallback(ServerWebExchange exchange) {
        return fallback("account-service");
    }

    @RequestMapping("/fallback/transaction")
    public Mono<ResponseEntity<Map<String, Object>>> transactionFallback(ServerWebExchange exchange) {
        return fallback("transaction-service");
    }

    @RequestMapping("/fallback/budget")
    public Mono<ResponseEntity<Map<String, Object>>> budgetFallback(ServerWebExchange exchange) {
        return fallback("budget-service");
    }

    @RequestMapping("/fallback/notification")
    public Mono<ResponseEntity<Map<String, Object>>> notificationFallback(ServerWebExchange exchange) {
        return fallback("notification-service");
    }

    private Mono<ResponseEntity<Map<String, Object>>> fallback(String serviceName) {
        log.warn("[CircuitBreaker] OPEN — service={}", serviceName);
        Map<String, Object> body = Map.of(
                "statusCode", HttpStatus.SERVICE_UNAVAILABLE.value(),
                "message", "서비스를 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도해주세요.",
                "service", serviceName
        );
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body));
    }
}
