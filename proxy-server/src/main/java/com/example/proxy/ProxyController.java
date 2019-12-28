package com.example.proxy;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.annotation.PostConstruct;
import java.net.ConnectException;
import java.util.Objects;

@RestController
public class ProxyController {

    private final String remoteHost = "localhost";
    private final Integer remotePort = 8080;

    private WebClient proxyClient;


    @PostConstruct
    void init() {
        proxyClient = WebClient.builder()
                .baseUrl(String.format("http://%s:%d", remoteHost, remotePort))
                .build();
    }


    @RequestMapping(value = "/**", method = {RequestMethod.GET, RequestMethod.POST})
    public Flux<DataBuffer> proxy(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();
        ServerHttpResponse response = exchange.getResponse();

        Mono<ClientResponse> monoRemoteResponse = proxyClient
                .method(Objects.requireNonNull(request.getMethod()))
                .uri(uriBuilder -> uriBuilder
                        .path(request.getPath().value())
                        .queryParams(request.getQueryParams())
                        .build())
                .headers(headers -> {
                    HttpHeaders requestHeaders = request.getHeaders();
                    requestHeaders.forEach(headers::addAll);
                })
                .body(request.getBody(), DataBuffer.class)
                .exchange();


        return Flux.from(monoRemoteResponse)
                .flatMap(remoteClientResponse -> {
                    response.setStatusCode(remoteClientResponse.statusCode());
                    ClientResponse.Headers remoteHeaders = remoteClientResponse.headers();
                    remoteHeaders.asHttpHeaders().forEach((name, values) -> {
                        if ("Content-Type".equalsIgnoreCase(name)) {
                            // デフォルトの「text/event-stream」を潰す
                            response.getHeaders().set(name, values.get(0));
                        } else {
                            response.getHeaders().addAll(name, values);
                        }
                    });

                    return remoteClientResponse.bodyToFlux(DataBuffer.class);
                })
                .doOnError(throwable -> {
                    if (throwable.getCause() != null && throwable.getCause() instanceof ConnectException) {
                        response.setStatusCode(HttpStatus.BAD_GATEWAY);
                    } else {
                        response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
                    }
                    response.getHeaders().set("Content-Type", "text/plain");
                })
                .onErrorReturn(response.bufferFactory().wrap(new byte[0]));
    }

}
