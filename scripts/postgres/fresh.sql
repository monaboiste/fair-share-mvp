SELECT pg_advisory_xact_lock(721947318621);
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_class WHERE relnamespace = 'public'::regnamespace)
        OR EXISTS (SELECT 1 FROM pg_proc WHERE pronamespace = 'public'::regnamespace)
        OR EXISTS (SELECT 1 FROM pg_type WHERE typnamespace = 'public'::regnamespace)
    THEN
        RAISE EXCEPTION 'Setup needs an empty public schema. Existing data stays unchanged.';
    END IF;
END
$$;
SET LOCAL search_path TO public;
