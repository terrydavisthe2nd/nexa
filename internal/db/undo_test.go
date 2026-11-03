package db

// undoCommunities takes away what migration 0032 adds, for the tests that
// take the schema back to an older version and run the migrations again.
const undoCommunities = `
DROP INDEX safety_reports_outcomes;
ALTER TABLE safety_reports DROP COLUMN outcome;
ALTER TABLE safety_reports DROP COLUMN outcome_at;
ALTER TABLE channels DROP COLUMN url;
DROP INDEX channels_server_nsfw;
ALTER TABLE servers DROP COLUMN nsfw;
	DROP INDEX servers_discoverable;
	DROP INDEX channels_starter;
	DROP INDEX audit_log_action;
	DROP INDEX audit_log_actor;
	DROP TABLE thread_members;
	DROP TABLE channel_follows;
	DROP TABLE crossposts;
	DROP TABLE automod_rules;
	DROP TABLE automod_events;
	ALTER TABLE roles DROP COLUMN color2;
	ALTER TABLE roles DROP COLUMN color3;
	ALTER TABLE roles DROP COLUMN icon_emoji;
	ALTER TABLE server_members DROP COLUMN banner_id;
	ALTER TABLE server_members DROP COLUMN bio;
	ALTER TABLE server_members DROP COLUMN pronouns;
	ALTER TABLE server_members DROP COLUMN join_method;
	ALTER TABLE server_members DROP COLUMN join_code;
	ALTER TABLE server_members DROP COLUMN invited_by;
	ALTER TABLE server_members DROP COLUMN accent_color;
	ALTER TABLE server_members DROP COLUMN accent_color_2;
	ALTER TABLE server_members DROP COLUMN widgets;
	ALTER TABLE servers DROP COLUMN invites_paused_until;
	ALTER TABLE servers DROP COLUMN discoverable;
	ALTER TABLE servers DROP COLUMN discovery_category;
	ALTER TABLE servers DROP COLUMN discovery_tags;
	ALTER TABLE servers DROP COLUMN discovery_language;
	ALTER TABLE servers DROP COLUMN discovery_since;
	ALTER TABLE servers DROP COLUMN discovery_hidden;
	ALTER TABLE servers DROP COLUMN verify_account_minutes;
	ALTER TABLE servers DROP COLUMN verify_member_minutes;
	ALTER TABLE servers DROP COLUMN automod_at;
	ALTER TABLE channels DROP COLUMN archived;
	ALTER TABLE channels DROP COLUMN archived_at;
	ALTER TABLE channels DROP COLUMN thread_locked;
	ALTER TABLE channels DROP COLUMN auto_archive;
	ALTER TABLE channels DROP COLUMN starter_id;
	ALTER TABLE channels DROP COLUMN message_count;
	ALTER TABLE channels DROP COLUMN applied_tags;
	ALTER TABLE channels DROP COLUMN forum;
`

// undoBots takes away what migrations 0034 to 0039 add, for the same tests.
const undoBots = `
	DROP TABLE gift_links;
	DROP TABLE plan_gifts;
	DROP TABLE server_boosts;
	DROP TABLE billing;
	ALTER TABLE servers DROP COLUMN boosts;
	ALTER TABLE users DROP COLUMN plan;
	DROP TABLE passkeys;
	ALTER TABLE users DROP COLUMN name_style;
	DROP TABLE user_ips;
	DROP TABLE bot_copies;
	DROP TABLE ephemeral_files;
	DROP TABLE interaction_files;
	DROP TABLE ephemeral_messages;
	DROP TABLE interactions;
	DROP TABLE oauth_tokens;
	DROP TABLE oauth_codes;
	DROP TABLE application_user_installs;
	DROP TABLE application_guild_installs;
	DROP TABLE application_command_permissions;
	DROP TABLE application_commands;
	DROP TABLE application_emojis;
	DROP TABLE applications;
	ALTER TABLE roles DROP COLUMN managed_by;
`
