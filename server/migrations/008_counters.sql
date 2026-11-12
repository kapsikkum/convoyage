-- Denormalised like and comment counters on posts, kept in step by triggers so feed pages read a column
-- instead of grouping post_likes / comments for every page.
alter table posts add column like_count integer not null default 0;
alter table posts add column comment_count integer not null default 0;

update posts set
  like_count = (select count(*) from post_likes l where l.post_id = posts.id),
  comment_count = (select count(*) from comments c where c.post_id = posts.id);

create function cv_post_like_count() returns trigger language plpgsql as $$
begin
  if tg_op = 'INSERT' then
    update posts set like_count = like_count + 1 where id = new.post_id;
  else
    update posts set like_count = greatest(like_count - 1, 0) where id = old.post_id;
  end if;
  return null;
end $$;

create trigger post_likes_count after insert or delete on post_likes
  for each row execute function cv_post_like_count();

create function cv_post_comment_count() returns trigger language plpgsql as $$
begin
  if tg_op = 'INSERT' then
    update posts set comment_count = comment_count + 1 where id = new.post_id;
  else
    update posts set comment_count = greatest(comment_count - 1, 0) where id = old.post_id;
  end if;
  return null;
end $$;

create trigger comments_count after insert or delete on comments
  for each row execute function cv_post_comment_count();
