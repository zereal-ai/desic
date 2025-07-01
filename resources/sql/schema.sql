-- DSPy Persistence Schema
-- Tables for storing optimization runs and metrics

CREATE TABLE IF NOT EXISTS runs (
  id            TEXT PRIMARY KEY,
  created_at    INTEGER,
  pipeline_blob TEXT
);

CREATE TABLE IF NOT EXISTS metrics (
  run_id     TEXT,
  iter       INTEGER,
  score      REAL,
  payload    TEXT,
  PRIMARY KEY (run_id, iter)
);