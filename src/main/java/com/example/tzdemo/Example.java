package com.example.tzdemo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "example")
public class Example {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "amount")
    private int amount;

    /**
     * Mapped to a Postgres column of type `timestamp` (== TIMESTAMP WITHOUT TIME ZONE).
     * This is the PROBLEMATIC pairing: Instant is an absolute point on the timeline,
     * but the column stores a zone-less wall-clock value. The conversion between the
     * two needs a reference zone, and without hibernate.jdbc.time_zone that zone is
     * the JVM default -> values drift across environments / DST.
     */
    @Column(name = "created_at")
    private Instant createdAt;

    /**
     * Mapped to a Postgres column of type `timestamptz` (== TIMESTAMP WITH TIME ZONE).
     * This is the CORRECT pairing for Instant: Postgres normalises to a UTC instant
     * internally, so no drift regardless of JVM/session time zone.
     */
    @Column(name = "created_at_tz")
    private Instant createdAtTz;

    public Long getId() {
        return id;
    }

    public int getAmount() {
        return amount;
    }

    public void setAmount(int amount) {
        this.amount = amount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getCreatedAtTz() {
        return createdAtTz;
    }

    public void setCreatedAtTz(Instant createdAtTz) {
        this.createdAtTz = createdAtTz;
    }
}
