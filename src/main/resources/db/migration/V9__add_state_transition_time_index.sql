-- The cross-company "what changed" feed reads transitions newest first with a
-- keyset cursor on (transitioned_at, id); the existing access was per company.
CREATE INDEX ix_state_transitions_transitioned_at
    ON state_transitions (transitioned_at DESC, id DESC);
