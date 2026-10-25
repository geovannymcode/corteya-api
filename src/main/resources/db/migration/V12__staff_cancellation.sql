ALTER TABLE appointment ADD COLUMN cancellation_reason text CHECK (length(cancellation_reason) <= 500);
ALTER TABLE appointment ADD COLUMN cancellation_origin text CHECK (cancellation_origin IN ('CLIENT', 'STAFF'));
