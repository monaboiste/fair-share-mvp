CREATE TABLE event_cursor (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    position BIGINT NOT NULL CHECK (position >= 0)
);
INSERT INTO event_cursor VALUES (TRUE, 0);

CREATE TABLE settlement_stream (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL CHECK (version > 0)
);

CREATE TABLE append_batch (
    first_position BIGINT PRIMARY KEY CHECK (first_position > 0),
    last_position BIGINT NOT NULL UNIQUE,
    stream_id UUID NOT NULL REFERENCES settlement_stream (id),
    CHECK (last_position >= first_position)
);

CREATE TABLE settlement_event (
    position BIGINT PRIMARY KEY CHECK (position > 0),
    batch_position BIGINT NOT NULL REFERENCES append_batch (first_position),
    stream_id UUID NOT NULL REFERENCES settlement_stream (id),
    sequence BIGINT NOT NULL CHECK (sequence > 0),
    event_id UUID NOT NULL UNIQUE,
    occurred_second BIGINT NOT NULL,
    occurred_nano INTEGER NOT NULL CHECK (occurred_nano BETWEEN 0 AND 999999999),
    event_type TEXT NOT NULL,
    schema_version INTEGER NOT NULL CHECK (schema_version > 0),
    payload JSONB NOT NULL,
    UNIQUE (stream_id, sequence)
);
CREATE INDEX settlement_event_batch ON settlement_event (batch_position, position);

CREATE TABLE settlement_projection (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL CHECK (version > 0),
    document JSONB NOT NULL
);
CREATE TABLE projection_checkpoint (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    position BIGINT NOT NULL CHECK (position >= 0)
);
INSERT INTO projection_checkpoint VALUES (TRUE, 0);
