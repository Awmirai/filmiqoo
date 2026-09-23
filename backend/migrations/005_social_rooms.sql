CREATE UNIQUE INDEX IF NOT EXISTS idx_unique_title_community_room
    ON rooms (media_title_id, room_type)
    WHERE media_title_id IS NOT NULL
      AND episode_id IS NULL
      AND room_type='community';

CREATE UNIQUE INDEX IF NOT EXISTS idx_unique_episode_room
    ON rooms (episode_id, room_type)
    WHERE episode_id IS NOT NULL
      AND room_type='episode';
