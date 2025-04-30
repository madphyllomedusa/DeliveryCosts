ALTER TABLE product ADD COLUMN normalized_name varchar(255);
UPDATE product
SET normalized_name = lower(regexp_replace(regexp_replace(name, '[[:punct:]]', '', 'g'), '\s+', '', 'g'));
ALTER TABLE product ALTER COLUMN normalized_name SET NOT NULL;
CREATE INDEX idx_product_normalized_name ON product(normalized_name);