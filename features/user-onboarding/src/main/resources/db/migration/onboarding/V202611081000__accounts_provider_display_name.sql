-- USR-007 / USR-013: remember the display name the identity provider last reported, so the
-- application copies it only when the provider reports a change and a name edited in Gavel
-- survives later requests. Existing rows start from their current display name.
ALTER TABLE users ADD COLUMN provider_display_name VARCHAR(255);
UPDATE users SET provider_display_name = display_name;
