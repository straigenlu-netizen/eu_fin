package dev.eufin.events;

import java.util.Properties;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration
@ConditionalOnProperty(name = "bank.streams-enabled", havingValue = "true")
public class StreamsConfiguration {
  @Bean(initMethod = "start", destroyMethod = "close")
  KafkaStreams streams(@Value("${spring.kafka.bootstrap-servers}") String brokers) {
    var p = new Properties();
    p.put(StreamsConfig.APPLICATION_ID_CONFIG, "eu-fin-status-metrics-v1");
    p.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
    p.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.StringSerde.class);
    p.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.StringSerde.class);
    p.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.EXACTLY_ONCE_V2);
    return new KafkaStreams(PaymentTopology.build(), p);
  }
}
