-- New events are emitted once, only on publication or the first interaction.
-- Existing notification push-outbox trigger handles delivery and retries.
CREATE UNIQUE INDEX community_notification_event_once
    ON notifications(user_id,actor_user_id,notification_type,entity_id)
    WHERE notification_type IN ('post_published','reel_published','discussion_reply','discussion_like');

CREATE FUNCTION notify_community_publication() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE author_id uuid; kind text; target_channel uuid;
BEGIN
    IF NEW.status <> 'published' THEN RETURN NEW; END IF;
    IF TG_OP = 'UPDATE' AND OLD.status = 'published' THEN RETURN NEW; END IF;
    IF TG_TABLE_NAME = 'posts' THEN
        author_id := NEW.author_user_id; kind := 'post';
    ELSE
        author_id := NEW.creator_user_id; kind := 'reel';
    END IF;
    target_channel := NEW.channel_id;
    INSERT INTO notifications(user_id,actor_user_id,notification_type,entity_type,entity_id,title,body)
    SELECT u.id,author_id,kind || '_published',kind,NEW.id,
           CASE WHEN kind='post' THEN 'پست تازه از دنبال‌شده‌ها' ELSE 'کلیپ تازه از دنبال‌شده‌ها' END,
           'یک تجربهٔ سینمایی تازه منتشر شده؛ برای دیدن باز کن.'
      FROM users u
      LEFT JOIN user_preferences pref ON pref.user_id=u.id
     WHERE u.id<>author_id AND u.status='active' AND COALESCE(pref.notifications_social,true)
       AND (EXISTS(SELECT 1 FROM user_follows f WHERE f.follower_user_id=u.id AND f.followed_user_id=author_id)
            OR EXISTS(SELECT 1 FROM channel_followers f WHERE f.user_id=u.id AND f.channel_id=target_channel))
       AND (target_channel IS NULL OR EXISTS(SELECT 1 FROM channels ch WHERE ch.id=target_channel AND
            (ch.visibility='public' OR EXISTS(SELECT 1 FROM channel_members cm WHERE cm.channel_id=ch.id AND cm.user_id=u.id))))
       AND NOT EXISTS(SELECT 1 FROM blocks b WHERE
            (b.blocker_user_id=u.id AND b.blocked_user_id=author_id) OR (b.blocker_user_id=author_id AND b.blocked_user_id=u.id))
       AND NOT EXISTS(SELECT 1 FROM user_mutes m WHERE m.muter_user_id=u.id AND m.muted_user_id=author_id)
    ON CONFLICT DO NOTHING;
    RETURN NEW;
END $$;
CREATE TRIGGER notify_post_publication AFTER INSERT OR UPDATE OF status ON posts FOR EACH ROW EXECUTE FUNCTION notify_community_publication();
CREATE TRIGGER notify_reel_publication AFTER INSERT OR UPDATE OF status ON reels FOR EACH ROW EXECUTE FUNCTION notify_community_publication();

CREATE FUNCTION notify_title_discussion_interaction() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE recipient uuid; actor_id uuid; target_id uuid; event_kind text;
BEGIN
    IF TG_TABLE_NAME='title_comments' THEN
        IF NEW.parent_id IS NULL OR NEW.deleted THEN RETURN NEW; END IF;
        target_id:=NEW.id;actor_id:=NEW.user_id;event_kind:='discussion_reply';
        SELECT user_id INTO recipient FROM title_comments WHERE id=NEW.parent_id AND NOT deleted;
    ELSE
        target_id:=NEW.comment_id;actor_id:=NEW.user_id;event_kind:='discussion_like';
        SELECT user_id INTO recipient FROM title_comments WHERE id=target_id AND NOT deleted;
    END IF;
    IF recipient IS NULL OR recipient=actor_id THEN RETURN NEW; END IF;
    INSERT INTO notifications(user_id,actor_user_id,notification_type,entity_type,entity_id,title,body)
    SELECT recipient,actor_id,event_kind,'title_comment',target_id,
           CASE WHEN event_kind='discussion_reply' THEN 'پاسخ تازه به دیدگاهت' ELSE 'دیدگاهت پسندیده شد' END,
           'گفت‌وگوی این عنوان را ببین؛ متن پاسخ برای جلوگیری از اسپویل در اعلان نمایش داده نمی‌شود.'
     WHERE COALESCE((SELECT notifications_social FROM user_preferences WHERE user_id=recipient),true)
       AND EXISTS(SELECT 1 FROM users WHERE id=recipient AND status='active')
       AND NOT EXISTS(SELECT 1 FROM blocks b WHERE
            (b.blocker_user_id=recipient AND b.blocked_user_id=actor_id) OR (b.blocker_user_id=actor_id AND b.blocked_user_id=recipient))
       AND NOT EXISTS(SELECT 1 FROM user_mutes m WHERE m.muter_user_id=recipient AND m.muted_user_id=actor_id)
    ON CONFLICT DO NOTHING;
    RETURN NEW;
END $$;
CREATE TRIGGER notify_title_reply AFTER INSERT ON title_comments FOR EACH ROW EXECUTE FUNCTION notify_title_discussion_interaction();
CREATE TRIGGER notify_title_like AFTER INSERT ON title_comment_likes FOR EACH ROW EXECUTE FUNCTION notify_title_discussion_interaction();
