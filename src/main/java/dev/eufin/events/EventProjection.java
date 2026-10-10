package dev.eufin.events;

import jakarta.persistence.*;

@Entity
@Table(name = "payment_view")
public class EventProjection {
  @Id public String id;
  public String status;

  @Column(name = "event_seq")
  public long eventSeq;

  protected EventProjection() {}
}
