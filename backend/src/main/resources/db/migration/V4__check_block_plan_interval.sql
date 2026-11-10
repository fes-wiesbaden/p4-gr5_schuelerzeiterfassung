ALTER TABLE block_plan
    ADD CONSTRAINT chk_block_plan_interval CHECK (starts_on <= ends_on);
