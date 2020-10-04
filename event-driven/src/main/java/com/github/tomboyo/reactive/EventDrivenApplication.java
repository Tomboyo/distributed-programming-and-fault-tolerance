package com.github.tomboyo.reactive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.client.RestTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuples;

import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

@SpringBootApplication
public class EventDrivenApplication {

    private static final Logger LOGGER = LoggerFactory.getLogger(EventDrivenApplication.class);

    private static final String OKAY = "ok";
    private static final String ERROR = "error";

    public static void main(String[] args) {
        SpringApplication.run(EventDrivenApplication.class, args);
    }

    @Bean
    public Supplier<Flux<Long>> clock() {
        return () -> Flux
                .<Long, Long>generate(
                        () -> 0L,
                        (state, sink) -> {
                            sink.next(state);
                            return state + 1L;
                        })
                .doOnNext(x -> LOGGER.info("clock generate - {}", x))
                .delayElements(Duration.ofMillis(500));
    }

    @Bean
    public Function<Flux<Message<Long>>, Flux<Message<Long>>> request() {
        var caller = caller();
        return flux -> flux
                .parallel(2)
                .runOn(Schedulers.boundedElastic())
                .map(m -> Tuples.of(m, caller.apply(m.getPayload()).block()))
                .sequential()
                .doOnNext(x -> LOGGER.info("request - {} {}", x.getT1().getPayload(), x.getT2()))
                // Handle only errors beyond this point
                .filter(t -> t.getT2().equals(ERROR))
                .map(t -> {
                    // Return a new message
                    var message = t.getT1();
                    return MessageBuilder.fromMessage(message)
                            .setHeader("x-retry",
                                    (int) message.getHeaders()
                                        .getOrDefault("x-retry", 0) + 1)
                            .build();
                })
                // Filter out any messages which have exceeded retry limit
                // We guarantee "x-retry" is set at this point
                .filter(message -> message.getHeaders().get("x-retry", Integer.class) < 3)
                .doOnNext(x -> LOGGER.info("retry {} for {}",
                        x.getHeaders().get("x-retry"),
                        x.getPayload()));
    }

    private Function<Long, Mono<String>> caller() {
        RestTemplate template = new RestTemplate();

        Function<Long, String> call = x -> {
            template.getForObject("http://localhost:8080/", String.class);
            return "OK";
        } ;

        return x -> Mono.fromSupplier(() -> call.apply(x))
                .doOnError(e -> LOGGER.debug("\t {} - {}", x, e.getMessage()))
                // per-request timeout based on the average response time of 600ms
                .timeout(Duration.ofMillis(800))
                .doOnError(e -> LOGGER.debug("\t {} - {}", x, e.getMessage()))
                // re-try any failure (including timeout) up to 3 times
                .retry(3)
                // "overall" timeout to prevent multiple slow retry attempts.
                // Three average-latency requests take 1800 ms overall.
                .timeout(Duration.ofSeconds(2))
                .doOnError(e -> LOGGER.debug("\t {} - {}", x, e.getMessage()))
                .map(y -> OKAY)
                .onErrorReturn(ERROR);
    }
}
