CREATE TABLE account (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    type TEXT NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL
);

CREATE TABLE category (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name TEXT NOT NULL,
    parent_id BIGINT,
    FOREIGN KEY (parent_id) REFERENCES category(id),
    UNIQUE NULLS NOT DISTINCT (parent_id, name)
);

CREATE TABLE import_file (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    filename TEXT NOT NULL,
    file_hash TEXT NOT NULL UNIQUE,
    imported_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    rows_total INT NOT NULL,
    rows_ok INT NOT NULL,
    rows_rejected INT NOT NULL,
    rows_duplicate INT NOT NULL
);

CREATE TABLE transaction (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id BIGINT NOT NULL,
    posted_date DATE NOT NULL,
    amount NUMERIC(10, 2) NOT NULL,
    description TEXT,
    normalized_description TEXT,
    category_id BIGINT,
    import_file_id BIGINT,
    dedup_key TEXT UNIQUE,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    FOREIGN KEY (account_id) REFERENCES account(id),
    FOREIGN KEY (category_id) REFERENCES category(id),
    FOREIGN KEY (import_file_id) REFERENCES import_file(id)
);

CREATE INDEX idx_transaction_account_date ON transaction(account_id, posted_date);
CREATE INDEX idx_transaction_category ON transaction(category_id);
CREATE INDEX idx_transaction_import_file ON transaction(import_file_id);