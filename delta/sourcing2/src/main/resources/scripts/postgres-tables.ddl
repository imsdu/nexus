CREATE TABLE IF NOT EXISTS public.events(
  ordering        BIGSERIAL,
  entity_type     text        NOT NULL,
  entity_id       text        NOT NULL,
  revision        integer     NOT NULL,
  scope           text        NOT NULL,
  payload         JSONB       NOT NULL,
  tracks          integer[]   NOT NULL,
  instant         timestamp   NOT NULL,
  written_at      timestamp   NOT NULL,
  write_version   text        NOT NULL,

  PRIMARY KEY(entity_type, entity_id, revision),
  UNIQUE (entity_id, revision, scope)
);

CREATE EXTENSION IF NOT EXISTS intarray WITH SCHEMA public;
CREATE INDEX events_tracks_idx ON public.events USING GIN (tracks public.gin__int_ops);
CREATE INDEX events_ordering_idx ON public.events USING BRIN (ordering);

CREATE TABLE IF NOT EXISTS public.tracks(
    id   SERIAL,
    name TEXT      NOT NULL,
    PRIMARY KEY (id)
);

CREATE UNIQUE INDEX IF NOT EXISTS tracks_name_idx on public.tracks (name);

CREATE TABLE IF NOT EXISTS public.states (
    ordering             BIGSERIAL,
    entity_type          text        NOT NULL,
    entity_id            text        NOT NULL,
    revision             integer     NOT NULL,
    payload              JSONB       NOT NULL,
    tracks               integer[],
    tag                  text,
    updated_at           timestamp   NOT NULL,
    written_at           timestamp   NOT NULL,
    write_version        text        NOT NULL,

  PRIMARY KEY(entity_type, entity_id, tag)
);

CREATE INDEX states_tracks_idx ON public.states USING GIN (tracks public.gin__int_ops);
CREATE INDEX states_ordering_idx ON public.states USING BRIN (ordering);

CREATE TABLE IF NOT EXISTS public.projection_progresses
(
    projection_id VARCHAR(255) NOT NULL,
    source_table        text       NOT NULL,
    metadata            JSONB       NOT NULL,
    current_offset      BIGINT,
    timestamp           timestamp  NOT NULL,
    processed           BIGINT     NOT NULL,
    discarded           BIGINT     NOT NULL,
    failed              BIGINT     NOT NULL,
    value               JSONB      NOT NULL,
    value_timestamp     timestamp  NOT NULL,
    PRIMARY KEY (projection_id)
);

CREATE TABLE IF NOT EXISTS public.projection_errors
(
    ordering            BIGSERIAL,
    projection_id       text          NOT NULL,
    source_table        text          NOT NULL,
    at_offset           BIGINT        NOT NULL,
    timestamp           timestamp     NOT NULL,
    entity_type         text          NOT NULL,
    entity_id           text          NOT NULL,
    revision            integer       NOT NULL,
    value_timestamp     timestamp     NOT NULL,
    error_type          text          NOT NULL,
    message             text          NOT NULL
);

CREATE INDEX IF NOT EXISTS projections_projection_id_idx ON public.projections_errors (projection_id);
CREATE INDEX IF NOT EXISTS projections_projection_error_type_idx ON public.projections_errors (error_type);
CREATE INDEX IF NOT EXISTS projections_failures_ordering_idx ON public.projections_errors (ordering);
