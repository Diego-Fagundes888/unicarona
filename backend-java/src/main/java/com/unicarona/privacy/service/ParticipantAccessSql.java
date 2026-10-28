package com.unicarona.privacy.service;

/** Fixed aliases shared by participant projections and direct private-photo downloads. */
public final class ParticipantAccessSql {
    private ParticipantAccessSql() {}
    // A pending financial confirmation is not an ongoing need for another person's contact.
    // The fixed upper bound also closes abandoned planned/active records without waiting for a worker.
    public static final String OPERATIONAL = """
        rr.status IN ('confirmed','boarded') AND r.status IN ('planned','active')
        AND now()<((r.ride_date+r.departure_time) AT TIME ZONE un.time_zone)+interval '48 hours'
        AND u.deleted_at IS NULL AND u.account_status<>'deleted'
        """;
}
