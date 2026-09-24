ALTER TABLE user_preferences
    ADD COLUMN IF NOT EXISTS download_requires_charging boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS download_battery_not_low boolean NOT NULL DEFAULT true,
    ADD COLUMN IF NOT EXISTS download_avoid_roaming boolean NOT NULL DEFAULT true;
