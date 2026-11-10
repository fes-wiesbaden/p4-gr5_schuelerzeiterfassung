ALTER TABLE block_assignment
    ADD CONSTRAINT chk_block_assignment_interval CHECK (starts_on <= ends_on);
