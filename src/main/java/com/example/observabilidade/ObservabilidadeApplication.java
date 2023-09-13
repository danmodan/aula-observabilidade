package com.example.observabilidade;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.function.Consumer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.repository.CrudRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.observabilidade.otel.OtelConfig;

import io.opentelemetry.api.trace.SpanKind;
import lombok.Data;
import lombok.RequiredArgsConstructor;

@EnableScheduling
@EnableFeignClients
@SpringBootApplication
public class ObservabilidadeApplication {

    public static void main(String[] args) {
        SpringApplication.run(ObservabilidadeApplication.class, args);
    }
}

@Component
@RequiredArgsConstructor
class ActivityScheduler {

    final ActivityService activityService;
    final ApplicationEventPublisher publisher;

    @Scheduled(cron = "${scheduler.getRandomActivityJob.cron}")
    void getRandomActivityJob() {
        var randomFound = activityService.getRandomFromClient();
        publisher.publishEvent(randomFound);
    }
}

@Configuration
@RequiredArgsConstructor
class ActivityStreamFunction {

    final ObservabilidadeClient observabilidadeClient;

    @Bean
    Consumer<Activity> saveActivityListener() {
        return activity -> observabilidadeClient.saveActivity(activity);
    }
}

@RestController
@RequestMapping("/activity")
@RequiredArgsConstructor
class ActivityController {

    final ActivityService activityService;
    final ApplicationEventPublisher publisher;

    @PostMapping
    @OtelConfig.Span(name = "sanlvandoactivitty", kind = SpanKind.CLIENT)
    void save(@RequestBody Activity activity) {
        activityService.save(activity);
    }

    @GetMapping("/random-from-client")
    Activity getRandomFromClient() {
        var randomFound = activityService.getRandomFromClient();
        publisher.publishEvent(randomFound);
        return randomFound;
    }

    @GetMapping
    Iterable<Activity> getAll() {
        return activityService.findAll();
    }

    @GetMapping("/{id}")
    ResponseEntity<Activity> getById(@PathVariable String id) {
        return activityService
            .findById(id)
            .map(activity -> ResponseEntity.ok(activity))
            .orElse(ResponseEntity.notFound().build());
    }
}

@Service
@RequiredArgsConstructor
class ActivityService {

    final ActivityRepository activityRepository;
    final ActivityClient activityClient;
    final StreamBridge streamBridge;

    Optional<Activity> findById(String id) {
        return activityRepository.findById(id);
    }

    Iterable<Activity> findAll() {
        return activityRepository.findAll();
    }

    Activity save(Activity activity) {
        return activityRepository.save(activity);
    }

    @OtelConfig.Span(name = "buscandoActivity", kind = SpanKind.CLIENT)
    Activity getRandomFromClient() {
        return activityClient.getRandom();
    }

    @EventListener
    void publish(Activity activity) {
        streamBridge.send("publishActivityOut", activity);
    }
}

@FeignClient("activity")
interface ActivityClient {

    @GetMapping("/api/activity")
    Activity getRandom();
}

@FeignClient("observabilidade")
interface ObservabilidadeClient {

    @PostMapping("/activity")
    void saveActivity(@RequestBody Activity activity);
}

@Repository
interface ActivityRepository extends CrudRepository<Activity, String> {}

@Data
@Document
class Activity {

    @Id
    String key;
    String activity;
    String type;
    Integer participants;
    BigDecimal price;
    String link;
}