package com.example.observabilidade.otel;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.core.annotation.Order;
import org.springframework.integration.config.GlobalChannelInterceptor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.semconv.SemanticAttributes;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;

@EnableAspectJAutoProxy
@Configuration
public class OtelConfig {

    @Bean
    OpenTelemetry openTelemetry() {
        return GlobalOpenTelemetry.get();
    }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    public static @interface Span {
        String name() default "";
        SpanKind kind() default SpanKind.INTERNAL;
    }

    @Aspect
    @Component
    class Pointcuts {

        @Pointcut("@annotation(span)")
        void span(OtelConfig.Span span) {}
    }

    @Aspect
    @Component
    class Advices {

        final OpenTelemetry openTelemetry;

        Advices(OpenTelemetry openTelemetry) {
            this.openTelemetry = openTelemetry;
        }

        @Order(100)
        @Around(
            value = "OtelConfig.Pointcuts.span(span)", 
            argNames = "span")
        Object spanAround(ProceedingJoinPoint jp, OtelConfig.Span span) throws Throwable {

            var tracerName = jp.getTarget().getClass().getName();
            var tracer = openTelemetry.getTracer(tracerName);
            var spanName = StringUtils.hasText(span.name()) ? span.name() : jp.getSignature().toShortString();
            String args = Stream.of(jp.getArgs()).map(Objects::toString).collect(Collectors.joining(","));
            var createdSpan = tracer
                .spanBuilder(spanName)
                .setSpanKind(span.kind())
                .setAttribute(SemanticAttributes.HTTP_REQUEST_METHOD, "POST")
                .setAttribute("bacenjud.flow", "unblock")
                .setAttribute("method.args", args)
                .startSpan();

            try (var scope = createdSpan.makeCurrent()) {
                var response = jp.proceed();
                if(response != null) {
                    createdSpan.setAttribute("method.result", response.toString());
                }
                return response;
            } catch (Throwable t) {
                createdSpan.setStatus(StatusCode.ERROR);
                createdSpan.recordException(t, Attributes.of(SemanticAttributes.EXCEPTION_ESCAPED, true));
                throw t;
            } finally {
                createdSpan.end();
            }
        }
    }
}

@Component
class FeignClientOtelContextPropagator implements RequestInterceptor {

    final OpenTelemetry openTelemetry;
    final TextMapSetter<RequestTemplate> setter;

    FeignClientOtelContextPropagator(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
        this.setter = (carrier, key, value) -> carrier.header(key, value);
    }

    @Override
    public void apply(RequestTemplate requestTemplate) {
        openTelemetry
            .getPropagators()
            .getTextMapPropagator()
            .inject(Context.current(), requestTemplate, setter);
    }
}

@Component
class HttpFilterOtelContextPropagator implements Filter {

    final OpenTelemetry openTelemetry;
    final TextMapGetter<HttpServletRequest> getter;

    HttpFilterOtelContextPropagator(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
        this.getter = new TextMapGetter<>() {
            @Override
            public Iterable<String> keys(HttpServletRequest carrier) {
                return () -> carrier.getHeaderNames().asIterator();
            }
            @Override
            public String get(HttpServletRequest carrier, String key) {
                return carrier.getHeader(key);
            }
        };
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {

        var httpRequest = (HttpServletRequest) request;

        var mergedContext = openTelemetry
            .getPropagators()
            .getTextMapPropagator()
            .extract(Context.current(), httpRequest, getter);

        try (var scope = mergedContext.makeCurrent()) {

            var tracer = openTelemetry.getTracer(HttpFilterOtelContextPropagator.class.getName());

            var createdSpan = tracer
                .spanBuilder("Filter OTEL_CP " + httpRequest.getMethod() + " " + httpRequest.getRequestURI())
                .setSpanKind(SpanKind.SERVER)
                .setAttribute(SemanticAttributes.HTTP_REQUEST_METHOD, httpRequest.getMethod())
                .setAttribute(SemanticAttributes.URL_SCHEME, request.getScheme())
                .setAttribute(SemanticAttributes.SERVER_ADDRESS, request.getLocalAddr() + ":" + request.getLocalPort())
                .setAttribute(SemanticAttributes.URL_PATH, httpRequest.getRequestURI())
                .setAttribute(SemanticAttributes.URL_QUERY, httpRequest.getQueryString())
                .startSpan();

            try (var scope2 = createdSpan.makeCurrent()) {
                chain.doFilter(request, response);
            } finally {
                createdSpan.end();
            }
        }
    }
}

@Component
@GlobalChannelInterceptor(patterns = "*")
class ChannelInterceptorOtelContextPropagator implements ChannelInterceptor {

    final OpenTelemetry openTelemetry;
    final TextMapSetter<Message> setter;

    ChannelInterceptorOtelContextPropagator(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
        this.setter = (carrier, key, value) -> carrier.getHeaders().putIfAbsent(key, value);
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {

        openTelemetry
            .getPropagators()
            .getTextMapPropagator()
            .inject(Context.current(), message, setter);

        return message;
    }
}
