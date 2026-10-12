CREATE TABLE operations_worker_heartbeat (
    worker_key varchar(40) PRIMARY KEY CHECK (worker_key = 'outbox'),
    last_tick_at timestamptz NOT NULL
);
