package dev.eufin.events;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@ConditionalOnProperty(name = "bank.kafka-enabled", havingValue = "true")
public class KafkaConfiguration {
  @Bean
  NewTopic paymentTopic() {
    return TopicBuilder.name("bank.payment-events.v1").partitions(3).replicas(1).build();
  }

  @Bean
  Listener listener(EventConsumer consumer) {
    return new Listener(consumer);
  }

  public static class Listener {
    private final EventConsumer consumer;

    Listener(EventConsumer consumer) {
      this.consumer = consumer;
    }

    @KafkaListener(topics = "bank.payment-events.v1")
    public void receive(String payload) {
      consumer.accept(payload);
    }
  }
}
