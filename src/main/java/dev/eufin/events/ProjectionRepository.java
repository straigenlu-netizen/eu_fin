package dev.eufin.events;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectionRepository extends JpaRepository<EventProjection, String> {}
