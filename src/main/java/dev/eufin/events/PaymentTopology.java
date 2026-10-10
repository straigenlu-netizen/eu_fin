package dev.eufin.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.*;
import org.apache.kafka.streams.kstream.*;

/** A KTable of latest aggregate state and counts by current status, not event counts. */
public final class PaymentTopology {
  private PaymentTopology() {}

  public static Topology build() {
    var builder = new StreamsBuilder();
    var json = new ObjectMapper();
    builder.stream("bank.payment-events.v1", Consumed.with(Serdes.String(), Serdes.String()))
        .groupByKey(Grouped.with(Serdes.String(), Serdes.String()))
        .aggregate(
            () -> "{\"sequence\":0,\"status\":\"NEW\"}",
            (key, value, old) -> {
              try {
                return json.readTree(value).required("sequence").asLong()
                        > json.readTree(old).required("sequence").asLong()
                    ? value
                    : old;
              } catch (Exception e) {
                throw new IllegalArgumentException("Invalid event", e);
              }
            },
            Materialized
                .<String, String,
                    org.apache.kafka.streams.state.KeyValueStore<
                        org.apache.kafka.common.utils.Bytes, byte[]>>
                    as("payments-latest")
                .withKeySerde(Serdes.String())
                .withValueSerde(Serdes.String()))
        .groupBy(
            (key, value) -> {
              try {
                return KeyValue.pair(json.readTree(value).required("status").asText(), value);
              } catch (Exception e) {
                throw new IllegalArgumentException(e);
              }
            },
            Grouped.with(Serdes.String(), Serdes.String()))
        .count(Materialized.as("payments-by-status"));
    return builder.build();
  }
}
